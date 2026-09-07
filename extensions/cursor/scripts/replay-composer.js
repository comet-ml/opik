#!/usr/bin/env node
/**
 * Build the trace and the span tree for a real Cursor conversation and print
 * them. Nothing is uploaded. This is the fastest way to check the span shape,
 * the durations and the payload sizes against real data.
 *
 * Usage: npm run compile && node scripts/replay-composer.js [composerId] [--full]
 *        node scripts/replay-composer.js --list
 */
const os = require('os');
const path = require('path');
const Module = require('module');

const originalLoad = Module._load;
Module._load = function (request) {
  if (request === 'vscode') {
    return {
      workspace: { getConfiguration: () => ({ get: (_key, fallback) => fallback }) },
      extensions: { getExtension: () => undefined },
    };
  }
  return originalLoad.apply(this, arguments);
};

const { executeQuery } = require('../out/cursor/sqlite.js');
const { groupBubblesByType } = require('../out/cursor/sessionManager.js');
const { buildSpans, buildTurnOutput, DEFAULT_SPAN_OPTIONS } = require('../out/cursor/spanBuilder.js');
const { orderBubbles } = require('../out/cursor/bubbleOrder.js');

const DB = path.join(
  os.homedir(),
  'Library/Application Support/Cursor/User/globalStorage/state.vscdb'
);

const query = (sql) => executeQuery(DB, sql);
const full = process.argv.includes('--full');

function fmtBytes(n) {
  if (n < 1024) return `${n}B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)}KB`;
  return `${(n / 1024 / 1024).toFixed(2)}MB`;
}

function size(value) {
  return value === undefined ? 0 : Buffer.byteLength(JSON.stringify(value));
}

async function listComposers() {
  const rows = await query(`
    SELECT key,
           json_extract(value,'$.name') AS name,
           json_extract(value,'$.lastUpdatedAt') AS updated
    FROM cursorDiskKV
    WHERE key >= 'composerData' AND key < 'composerDatb'
    ORDER BY updated DESC LIMIT 20`);
  for (const row of rows) {
    const id = String(row.key).split(':')[1];
    console.log(`${id}  ${new Date(Number(row.updated)).toISOString()}  ${row.name ?? ''}`);
  }
}

async function loadConversation(composerId) {
  const composerRows = await query(
    `SELECT value FROM cursorDiskKV WHERE key = 'composerData:${composerId}'`
  );
  if (!composerRows.length) {
    throw new Error(`No composer found with id ${composerId}`);
  }
  const composerData = JSON.parse(composerRows[0].value);

  const bubbleRows = await query(
    `SELECT key, value FROM cursorDiskKV
     WHERE key >= 'bubbleId:${composerId}:' AND key < 'bubbleId:${composerId};'`
  );

  const byId = new Map();
  for (const row of bubbleRows) {
    const chatData = JSON.parse(row.value);
    const id = String(row.key).split(':')[2];
    byId.set(id, {
      ...chatData,
      id,
      type: chatData.type === 1 ? 'user' : chatData.type === 2 ? 'ai' : 'unknown',
      text: chatData.text || chatData.content || '',
      resolvedTimestamp: Date.parse(chatData.createdAt) || composerData.createdAt,
    });
  }

  const ordered = orderBubbles([...byId.values()], composerData.fullConversationHeadersOnly);

  return {
    conversation: {
      chatTitle: composerData.name,
      composerId,
      createdAt: composerData.createdAt,
      model: composerData.modelConfig?.modelName,
      bubbleCount: ordered.length,
      unifiedMode: composerData.unifiedMode,
      isAgentic: composerData.isAgentic,
      createdOnBranch: composerData.createdOnBranch,
      contextTokensUsed: composerData.contextTokensUsed,
      contextTokenLimit: composerData.contextTokenLimit,
    },
    bubbles: ordered,
    usageData: composerData.usageData,
  };
}

(async () => {
  if (process.argv.includes('--list')) {
    await listComposers();
    return;
  }

  let composerId = process.argv[2];
  if (!composerId || composerId.startsWith('--')) {
    const rows = await query(`
      SELECT key FROM cursorDiskKV
      WHERE key >= 'composerData' AND key < 'composerDatb'
      ORDER BY json_extract(value,'$.lastUpdatedAt') DESC LIMIT 1`);
    composerId = String(rows[0].key).split(':')[1];
  }

  const { conversation, bubbles, usageData } = await loadConversation(composerId);
  const groups = groupBubblesByType(bubbles);

  console.log(`composer:  ${composerId}`);
  console.log(`title:     ${conversation.chatTitle}`);
  console.log(`model:     ${conversation.model}   mode: ${conversation.unifiedMode}`);
  console.log(`bubbles:   ${bubbles.length}   turns: ${groups.length}`);
  console.log(`usageData: ${JSON.stringify(usageData ?? {})}`);
  console.log();

  const totals = { spans: 0, llm: 0, tool: 0, general: 0, bytes: 0, truncated: 0 };
  const rawBytes = Buffer.byteLength(JSON.stringify(bubbles));

  groups.forEach((group, index) => {
    if (!group.userMessages.length || !group.aiMessages.length) {
      return;
    }
    const spans = buildSpans(group, conversation, DEFAULT_SPAN_OPTIONS);
    const turnStart = group.userMessages[0].resolvedTimestamp;
    const question = (group.userMessages[0].text || '').replace(/\s+/g, ' ').slice(0, 70);
    let previousStart = turnStart;

    console.log(`── turn ${index + 1}  (${spans.length} spans)  "${question}"`);

    if (full) {
      console.log('   trace output:');
      for (const line of buildTurnOutput(group.aiMessages).split('\n')) {
        console.log(`     | ${line.slice(0, 100)}`);
      }
      console.log();
    }

    for (const span of spans) {
      const ms = span.endTime.getTime() - span.startTime.getTime();
      const offset = span.startTime.getTime() - turnStart;
      const bytes = size(span.input) + size(span.output);
      const flags = [
        span.errorInfo ? 'ERROR' : '',
        span.metadata?.output_truncated || span.metadata?.input_truncated ? 'trunc' : '',
      ].filter(Boolean).join(',');

      console.log(
        `   ${String(span.type).padEnd(7)} ${String(span.name).padEnd(26)}` +
        ` +${String(Math.round(offset / 1000)).padStart(4)}s` +
        ` ${String(ms).padStart(7)}ms` +
        ` ${fmtBytes(bytes).padStart(8)}` +
        (flags ? `  ${flags}` : '')
      );

      if (full) {
        if (span.input !== undefined) console.log(`      in : ${JSON.stringify(span.input).slice(0, 300)}`);
        if (span.output !== undefined) console.log(`      out: ${JSON.stringify(span.output).slice(0, 300)}`);
      }

      totals.spans += 1;
      totals[span.type] += 1;
      totals.bytes += bytes;
      if (flags.includes('trunc')) totals.truncated += 1;

      if (ms < 0) {
        console.log(`      !! negative duration`);
      }
      if (span.startTime.getTime() < turnStart) {
        console.log(`      !! starts before the turn`);
      }
      if (span.startTime.getTime() < previousStart - 1000) {
        console.log(`      !! starts ${Math.round((previousStart - span.startTime.getTime()) / 1000)}s before the span above`);
      }
      previousStart = Math.max(previousStart, span.startTime.getTime());
    }
    console.log();
  });

  console.log('─'.repeat(60));
  console.log(`spans:        ${totals.spans}  (llm ${totals.llm}, tool ${totals.tool}, general ${totals.general})`);
  console.log(`span payload: ${fmtBytes(totals.bytes)}   truncated spans: ${totals.truncated}`);
  console.log(`raw bubbles:  ${fmtBytes(rawBytes)}  (what the old metadata copy used to carry)`);
})().catch((error) => {
  console.error(error.stack || error.message);
  process.exit(1);
});
