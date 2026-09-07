#!/usr/bin/env node
/**
 * Unit tests for the span builder and the bubble ordering.
 * The fixtures copy the shapes found in a real Cursor database.
 *
 * Usage: npm run compile && npm run test-spans
 */
const test = require('node:test');
const assert = require('node:assert');

const { classifyBubble, toolName, TOOL_ID_NAMES } = require('../out/cursor/bubbleKinds.js');
const { assignWindows, splitIntoModelCalls } = require('../out/cursor/modelCalls.js');
const { buildSpans, buildTurnOutput, truncateForSpan, DEFAULT_SPAN_OPTIONS } = require('../out/cursor/spanBuilder.js');
const { orderBubbles } = require('../out/cursor/bubbleOrder.js');

const T0 = Date.parse('2026-01-10T19:57:00.000Z');
const at = (seconds, ms = 0) => new Date(T0 + seconds * 1000 + ms).toISOString();

const user = (seconds, text) => ({ id: `u${seconds}`, type: 'user', text, createdAt: at(seconds) });
const think = (seconds, durationMs, text = 'reasoning') => ({
    id: `k${seconds}`, type: 'ai', capabilityType: 30, createdAt: at(seconds),
    thinking: { text }, thinkingDurationMs: durationMs, thinkingStyle: 1,
});
const message = (seconds, text) => ({ id: `m${seconds}`, type: 'ai', createdAt: at(seconds), text });
const tool = (seconds, name, modelCallId, extra = {}) => ({
    id: `t${seconds}-${name}`, type: 'ai', capabilityType: 15, createdAt: at(seconds), text: '',
    toolFormerData: {
        tool: 40, name, modelCallId, toolCallId: `call-${name}`, status: 'completed',
        rawArgs: JSON.stringify({ target_file: `${name}.ts` }),
        result: JSON.stringify({ contents: 'ok' }),
        ...extra,
    },
});
const errorBubble = (seconds, msg) => ({
    id: `e${seconds}`, type: 'ai', createdAt: at(seconds), text: '',
    errorDetails: { message: msg, requestId: 'req-1', stackTrace: 'at x\nat y' },
});

const conversation = { composerId: 'c1', model: 'claude-4.5-opus-high', createdAt: T0 };
const kinds = (windows) => windows.map(w => w.kind);

test('a placeholder toolFormerData is not a tool call', () => {
    // 1237 of 19691 real bubbles carry exactly this and are ordinary messages.
    const bubble = { type: 'ai', text: 'hello', toolFormerData: { additionalData: { status: 'error' } } };
    assert.equal(classifyBubble(bubble), 'message');
});

test('classifyBubble handles both forms of the type field', () => {
    assert.equal(classifyBubble({ type: 1, text: 'hi' }), 'user');
    assert.equal(classifyBubble({ type: 'user', text: 'hi' }), 'user');
});

test('a tool call wins over the thinking on the same bubble', () => {
    const bubble = { type: 'ai', thinking: { text: 'x' }, toolFormerData: { tool: 40, name: 'read_file' } };
    assert.equal(classifyBubble(bubble), 'tool');
});

test('an error bubble is classified even with a placeholder toolFormerData', () => {
    const bubble = errorBubble(1, 'Network disconnected [unknown]');
    bubble.toolFormerData = { additionalData: { status: 'error' } };
    assert.equal(classifyBubble(bubble), 'error');
});

test('an empty bubble is skipped', () => {
    assert.equal(classifyBubble({ type: 'ai', text: '   ' }), 'skip');
    assert.equal(classifyBubble(undefined), 'skip');
});

test('toolName falls back to the numeric id when the name is gone', () => {
    assert.equal(toolName({ tool: 41, name: 'ripgrep_raw_search' }), 'ripgrep_raw_search');
    assert.equal(toolName({ tool: 41 }), TOOL_ID_NAMES[41]);
    assert.equal(toolName({ tool: '15' }), 'run_terminal_cmd');
    assert.equal(toolName({}), 'unknown_tool');
});

test('a step starts where the previous step ended', () => {
    const windows = assignWindows([message(2, 'a'), tool(5, 'read_file', 'A')], T0);
    assert.deepEqual(kinds(windows), ['message', 'tool']);
    assert.equal(windows[0].startMs, T0);
    assert.equal(windows[0].endMs, T0 + 2000);
    assert.equal(windows[1].startMs, T0 + 2000);
    assert.equal(windows[1].endMs, T0 + 5000);
});

test('tools that share a timestamp share a start', () => {
    const windows = assignWindows(
        [message(2, 'a'), tool(5, 'read_file', 'A'), tool(5, 'grep', 'B')],
        T0
    );
    assert.equal(windows[1].startMs, T0 + 2000);
    assert.equal(windows[2].startMs, T0 + 2000, 'the parallel tool must not start at its own end');
});

test('thinking uses its exact duration', () => {
    const windows = assignWindows([message(2, 'a'), think(10, 3000)], T0);
    assert.equal(windows[1].startMs, T0 + 7000);
    assert.equal(windows[1].endMs, T0 + 10000);
});

test('a negative thinking duration collapses to zero instead of going backwards', () => {
    // thinkingDurationMs reaches -1000 in the real database.
    const windows = assignWindows([message(2, 'a'), think(10, -1000)], T0);
    assert.equal(windows[1].startMs, T0 + 10000);
    assert.equal(windows[1].endMs, T0 + 10000);
});

test('thinking flushed with the tool before it does not overlap that tool', () => {
    // Cursor writes the tool bubble and the next reasoning at the same instant.
    const windows = assignWindows(
        [message(2, 'a'), tool(9, 'read_file', 'A'), think(9, 4000)],
        T0
    );
    assert.equal(windows[1].startMs, T0 + 2000);
    assert.equal(windows[1].endMs, T0 + 9000);
    assert.equal(windows[2].startMs, T0 + 9000, 'reasoning cannot start before the tool finished');
    assert.equal(windows[2].endMs, T0 + 9000);
});

test('an error bubble is a point event and does not move the cursor', () => {
    const windows = assignWindows(
        [message(2, 'a'), errorBubble(60, 'PING timed out [unavailable]'), tool(9, 'read_file', 'A')],
        T0
    );
    assert.equal(windows[1].startMs, windows[1].endMs);
    assert.equal(windows[1].startMs, T0 + 60000);
    assert.equal(windows[2].startMs, T0 + 2000, 'the error must not squash the next step');
    assert.equal(windows[2].endMs, T0 + 9000);
});

test('a reasoning model is cut into one call per thinking block', () => {
    const windows = assignWindows([
        think(1, 500), message(2, 'a'), tool(3, 'read_file', 'A'),
        think(3, 800), message(4, 'b'), tool(5, 'write', 'B'),
        think(5, 400), tool(6, 'run_terminal_cmd', 'C'),
    ], T0);
    const calls = splitIntoModelCalls(windows);
    assert.equal(calls.length, 3);
    assert.deepEqual(calls.map(c => c.output.length), [2, 2, 1]);
    assert.deepEqual(calls.map(c => c.steps.length), [1, 1, 1]);
});

test('a model without reasoning is cut at each text block', () => {
    const windows = assignWindows([
        message(1, 'a'), tool(2, 'read_file', 'A'),
        message(3, 'b'), tool(4, 'grep', 'B'),
    ], T0);
    const calls = splitIntoModelCalls(windows);
    assert.equal(calls.length, 2);
    assert.deepEqual(calls.map(c => c.steps.length), [1, 1]);
});

test('tools dispatched together stay in one model call', () => {
    const windows = assignWindows([
        message(1, 'a'), tool(4, 'read_file', 'A'), tool(4, 'grep', 'B'),
    ], T0);
    const calls = splitIntoModelCalls(windows);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].steps.length, 2);
});

test('a different model call at a different time starts a new call', () => {
    const windows = assignWindows([
        message(1, 'a'), tool(4, 'read_file', 'A'), tool(9, 'grep', 'B'),
    ], T0);
    assert.equal(splitIntoModelCalls(windows).length, 2);
});

test('a payload under the limit is untouched', () => {
    const result = truncateForSpan({ a: 'short' }, 1000);
    assert.equal(result.truncated, false);
    assert.deepEqual(result.value, { a: 'short' });
});

test('a payload over the limit keeps its start and its end', () => {
    const value = { head: 'H'.repeat(500), tail: 'T'.repeat(500) };
    const result = truncateForSpan(value, 200);
    assert.equal(result.truncated, true);
    assert.equal(typeof result.value, 'string');
    assert.ok(result.value.startsWith('{"head":"HHH'));
    assert.ok(result.value.endsWith('TTT"}'));
    assert.ok(result.value.includes('truncated'));
    assert.ok(result.originalLength > 1000);
});

test('a base64 screenshot is replaced before the size check', () => {
    const image = 'data:image/png;base64,' + 'A'.repeat(200000);
    const result = truncateForSpan({ content: image }, 10000);
    assert.equal(result.truncated, false);
    assert.match(result.value.content, /^\[binary, \d+ bytes\]$/);
});

test('buildSpans emits llm, tool and general spans in order', () => {
    const group = {
        userMessages: [user(0, 'do the thing')],
        aiMessages: [
            think(1, 500), message(2, 'looking'), tool(6, 'read_file', 'A'),
            think(6, 900), tool(11, 'grep', 'B'),
            errorBubble(20, 'PING timed out [unavailable]'),
        ],
    };
    const spans = buildSpans(group, conversation, DEFAULT_SPAN_OPTIONS);

    assert.deepEqual(spans.map(s => s.type), ['llm', 'tool', 'llm', 'tool', 'general']);
    assert.deepEqual(spans.map(s => s.name),
        ['assistant', 'read_file', 'assistant', 'grep', 'cursor-error']);

    assert.equal(spans[0].provider, 'cursor');
    assert.equal(spans[0].model, 'claude-4.5-opus-high');
    assert.equal(spans[0].input, undefined, 'Cursor does not store the prompt');
    assert.equal(spans[0].output.thinking, 'reasoning');
    assert.equal(spans[0].output.text, 'looking');
    assert.deepEqual(spans[0].output.tool_calls, [{ name: 'read_file', arguments: { target_file: 'read_file.ts' } }]);

    assert.deepEqual(spans[1].input, { target_file: 'read_file.ts' });
    assert.deepEqual(spans[1].output, { contents: 'ok' });
    assert.equal(spans[1].metadata.model_call_id, 'A');
    assert.equal(spans[1].errorInfo, undefined);

    assert.equal(spans[4].errorInfo.message, 'PING timed out [unavailable]');
    assert.equal(spans[4].errorInfo.exceptionType, 'cursor-request-error');
    assert.equal(spans[4].startTime.getTime(), spans[4].endTime.getTime());
});

test('a failed tool carries errorInfo', () => {
    const group = {
        userMessages: [user(0, 'run it')],
        aiMessages: [
            message(1, 'running'),
            tool(4, 'run_terminal_cmd', 'A', { status: 'error', error: JSON.stringify('exit 1') }),
        ],
    };
    const spans = buildSpans(group, conversation, DEFAULT_SPAN_OPTIONS);
    const toolSpan = spans.find(s => s.type === 'tool');
    assert.equal(toolSpan.errorInfo.exceptionType, 'cursor-tool-error');
    assert.ok(toolSpan.tags.includes('error'));
});

test('a model call with only tool bubbles emits no zero width llm span', () => {
    const group = {
        userMessages: [user(0, 'go')],
        aiMessages: [tool(3, 'read_file', 'A'), tool(8, 'grep', 'B')],
    };
    const spans = buildSpans(group, conversation, DEFAULT_SPAN_OPTIONS);
    assert.deepEqual(spans.map(s => s.type), ['tool', 'tool']);
});

test('every span stays inside the turn and moves forward', () => {
    const group = {
        userMessages: [user(0, 'go')],
        aiMessages: [think(1, 90000), message(2, 'a'), tool(6, 'read_file', 'A')],
    };
    for (const span of buildSpans(group, conversation, DEFAULT_SPAN_OPTIONS)) {
        assert.ok(span.endTime.getTime() >= span.startTime.getTime(), `${span.name} runs backwards`);
        assert.ok(span.startTime.getTime() >= T0, `${span.name} starts before the turn`);
    }
});

test('the span cap keeps the ends and reports what was dropped', () => {
    const aiMessages = [];
    for (let i = 1; i <= 40; i++) {
        aiMessages.push(message(i * 2, `step ${i}`), tool(i * 2 + 1, 'read_file', `M${i}`));
    }
    const spans = buildSpans({ userMessages: [user(0, 'go')], aiMessages }, conversation,
        { maxPayloadChars: 10000, maxSpansPerTurn: 10 });

    assert.equal(spans.length, 11);
    const marker = spans.find(s => s.name === 'truncated-steps');
    assert.equal(marker.metadata.total_span_count, 80);
    assert.equal(marker.metadata.dropped_span_count, 70);
});

test('orderBubbles keeps the header order', () => {
    const bubbles = [message(5, 'c'), message(1, 'a'), message(3, 'b')];
    const headers = [{ bubbleId: 'm1' }, { bubbleId: 'm3' }, { bubbleId: 'm5' }];
    assert.deepEqual(orderBubbles(bubbles, headers).map(b => b.id), ['m1', 'm3', 'm5']);
});

test('orderBubbles puts an unlisted error bubble in by time', () => {
    const bubbles = [message(1, 'a'), message(9, 'c'), errorBubble(5, 'boom')];
    const headers = [{ bubbleId: 'm1' }, { bubbleId: 'm9' }];
    assert.deepEqual(orderBubbles(bubbles, headers).map(b => b.id), ['m1', 'e5', 'm9']);
});

test('orderBubbles drops unlisted bubbles that are not errors', () => {
    // A composer keeps bubbles from branches the user edited away. One real
    // composer holds 833 bubbles for 748 headers.
    const bubbles = [message(1, 'a'), message(5, 'abandoned'), message(9, 'c')];
    const headers = [{ bubbleId: 'm1' }, { bubbleId: 'm9' }];
    assert.deepEqual(orderBubbles(bubbles, headers).map(b => b.id), ['m1', 'm9']);
});

test('orderBubbles drops an error from outside the retained thread', () => {
    const bubbles = [message(100, 'a'), message(200, 'c'), errorBubble(5, 'boom')];
    const headers = [{ bubbleId: 'm100' }, { bubbleId: 'm200' }];
    assert.deepEqual(orderBubbles(bubbles, headers).map(b => b.id), ['m100', 'm200']);
});

test('the turn output interleaves the tool names with the messages', () => {
    const aiMessages = [
        think(1, 500), message(2, 'Let me check the file.'),
        tool(6, 'read_file', 'A'), tool(6, 'grep', 'A'),
        message(8, 'The import is missing.'),
        tool(11, 'search_replace', 'B'),
    ];
    assert.equal(buildTurnOutput(aiMessages), [
        'Let me check the file.',
        '',
        '[read_file]',
        '[grep]',
        '',
        'The import is missing.',
        '',
        '[search_replace]',
    ].join('\n'));
});

test('the turn output keeps reasoning out', () => {
    const aiMessages = [think(1, 500, 'private reasoning'), message(2, 'done')];
    assert.equal(buildTurnOutput(aiMessages), 'done');
});

test('a turn of only tool calls still produces an output', () => {
    const aiMessages = [tool(3, 'read_file', 'A'), tool(8, 'grep', 'B')];
    assert.equal(buildTurnOutput(aiMessages), '[read_file]\n[grep]');
});

test('the turn output uses the tool id when the name is gone', () => {
    const bubble = tool(3, 'read_file', 'A');
    delete bubble.toolFormerData.name;
    assert.equal(buildTurnOutput([bubble]), `[${TOOL_ID_NAMES[40]}]`);
});

test('the turn output is empty when the assistant did nothing', () => {
    assert.equal(buildTurnOutput([think(1, 500), { type: 'ai', text: '  ' }]), '');
});
