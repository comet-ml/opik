#!/usr/bin/env node
/**
 * End-to-end check of the Cursor usage pipeline, without the extension host.
 * Reads the newest local conversation, fetches its usage from Cursor, runs the
 * compiled attribution, and reconciles the per-turn totals against the API.
 *
 * Usage: npm run compile && node scripts/verify-usage.js [composerId]
 */
const os = require('os');
const path = require('path');
const Module = require('module');

const originalLoad = Module._load;
Module._load = function (request) {
  if (request === 'vscode') {
    return { workspace: { getConfiguration: () => ({ get: (_key, fallback) => fallback }) } };
  }
  return originalLoad.apply(this, arguments);
};

const { attributeUsageToTurns } = require('../out/cursor/usage.js');
const { executeQuery } = require('../out/cursor/sqlite.js');
const { toSpanUsage } = require('../out/opik.js');

const DB = path.join(
  os.homedir(),
  'Library/Application Support/Cursor/User/globalStorage/state.vscdb'
);

const query = (sql) => executeQuery(DB, sql);

async function rpc(method, token, body) {
  const response = await fetch(`https://api2.cursor.sh/aiserver.v1.DashboardService/${method}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
      'connect-protocol-version': '1',
    },
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    throw new Error(`${method} -> ${response.status} ${await response.text()}`);
  }
  return response.json();
}

(async () => {
  const token = (await query(`SELECT value FROM ItemTable WHERE key='cursorAuth/accessToken'`))[0]?.value;
  if (!token) {
    console.error('No Cursor access token found. Sign in to Cursor first.');
    process.exit(1);
  }
  console.log(`token: present (${token.length} chars)`);

  const me = await rpc('GetMe', token, {});
  console.log(`user: ${me.email}  userId=${me.userId}  teamAdmin=${me.isTeamAdmin}`);

  const composerId =
    process.argv[2] ||
    (await query(
      `SELECT composerId FROM composerHeaders WHERE composerId != 'empty-state-draft'
       ORDER BY lastUpdatedAt DESC LIMIT 1`
    ))[0]?.composerId;
  if (!composerId) {
    console.error('No conversation found.');
    process.exit(1);
  }

  const raw = (await query(`SELECT value FROM cursorDiskKV WHERE key='composerData:${composerId}'`))[0];
  const composer = JSON.parse(raw.value);
  console.log(`\nconversation: ${composerId}`);
  console.log(`name: ${composer.name}`);
  console.log(`model: ${composer.modelConfig?.modelName}`);

  // Read from the bubble rows, the same source the extension uses. Older
  // conversations have no createdAt on the header entries.
  const bubbleRows = await query(
    `SELECT json_extract(value, '$.createdAt') AS createdAt FROM cursorDiskKV
     WHERE key >= 'bubbleId:${composerId}:' AND key < 'bubbleId:${composerId};'
     AND json_extract(value, '$.type') = 1`
  );
  const turnStarts = [
    ...new Set(bubbleRows.map((r) => Date.parse(r.createdAt)).filter((v) => !Number.isNaN(v))),
  ].sort((a, b) => a - b);
  if (turnStarts.length === 0) {
    console.error('No user turns with timestamps in this conversation.');
    process.exit(1);
  }

  const result = await rpc('GetFilteredUsageEvents', token, {
    userId: me.userId,
    startDate: String(turnStarts[0] - 60_000),
    endDate: String(Date.now() + 60_000),
    page: 1,
    pageSize: 100,
  });
  const events = (result.usageEventsDisplay || []).filter((e) => e.conversationId === composerId);
  console.log(`turns: ${turnStarts.length}   usage events: ${events.length}\n`);

  const attributed = attributeUsageToTurns(turnStarts, events);
  const sum = { input: 0, output: 0, cacheRead: 0, cents: 0 };
  let covered = 0;

  turnStarts.forEach((start, i) => {
    const usage = attributed.get(i);
    const at = new Date(start).toLocaleTimeString();
    if (!usage) {
      console.log(`  turn ${i + 1} [${at}] no usage event`);
      return;
    }
    covered++;
    sum.input += usage.inputTokens;
    sum.output += usage.outputTokens;
    sum.cacheRead += usage.cacheReadTokens;
    sum.cents += usage.chargedCents;
    const emitted = toSpanUsage(usage);
    console.log(
      `  turn ${i + 1} [${at}] reqs=${usage.requestCount} in=${usage.inputTokens} ` +
        `out=${usage.outputTokens} cacheR=${usage.cacheReadTokens} ` +
        `cents=${usage.chargedCents.toFixed(4)} model=${usage.models.join(',')}`
    );
    console.log(
      `        -> opik prompt_tokens=${emitted.prompt_tokens} ` +
        `completion_tokens=${emitted.completion_tokens} total_tokens=${emitted.total_tokens}`
    );
  });

  const total = events.reduce(
    (acc, e) => ({
      input: acc.input + (e.tokenUsage?.inputTokens ?? 0),
      output: acc.output + (e.tokenUsage?.outputTokens ?? 0),
      cacheRead: acc.cacheRead + (e.tokenUsage?.cacheReadTokens ?? 0),
      cents: acc.cents + (e.chargedCents ?? 0),
    }),
    { input: 0, output: 0, cacheRead: 0, cents: 0 }
  );

  const reconciled =
    sum.input === total.input &&
    sum.output === total.output &&
    sum.cacheRead === total.cacheRead &&
    Math.abs(sum.cents - total.cents) < 1e-9;

  const signatures = [];
  turnStarts.forEach((_s, i) => {
    const u = attributed.get(i);
    if (u) signatures.push(`${u.inputTokens}/${u.outputTokens}/${u.cacheReadTokens}`);
  });
  const duplicates = signatures.length - new Set(signatures).size;

  // Assert the exact mapping that applyTurnUsage sends, not a copy of it. Cursor
  // reports inputTokens excluding cache, so prompt_tokens must include the cache
  // tokens or the reported totals undercount by several times.
  let mappingOk = true;
  turnStarts.forEach((_s, i) => {
    const u = attributed.get(i);
    if (!u) return;
    const emitted = toSpanUsage(u);
    const expectedPrompt = u.inputTokens + u.cacheReadTokens + u.cacheWriteTokens;
    if (
      emitted.prompt_tokens !== expectedPrompt ||
      emitted.total_tokens !== expectedPrompt + u.outputTokens ||
      emitted.cache_read_input_tokens !== u.cacheReadTokens
    ) {
      mappingOk = false;
      console.log(`  turn ${i + 1}: MAPPING MISMATCH ${JSON.stringify(emitted)}`);
    }
  });

  console.log(`\ncoverage:   ${covered}/${turnStarts.length} turns`);
  console.log(`duplicates: ${duplicates} turns share an identical usage payload`);
  console.log(`mapping:    ${mappingOk ? 'prompt_tokens includes cache tokens' : 'MISMATCH'}`);
  console.log(`attributed: in=${sum.input} out=${sum.output} cacheR=${sum.cacheRead} cents=${sum.cents.toFixed(4)}`);
  console.log(`api total:  in=${total.input} out=${total.output} cacheR=${total.cacheRead} cents=${total.cents.toFixed(4)}`);
  console.log(`reconcile:  ${reconciled ? 'PASS' : 'FAIL'}`);
  process.exit(reconciled && duplicates === 0 && mappingOk ? 0 : 1);
})().catch((error) => {
  console.error(error.message);
  process.exit(1);
});
