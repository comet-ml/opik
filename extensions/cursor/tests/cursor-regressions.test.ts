import assert = require('node:assert/strict');
import test = require('node:test');

import { composerIdFromKey, resolveCanonicalBubbleOwners, isCursorComposerId } from '../src/cursor/composerIdentity';
import { compactRequestLedger, REQUEST_LEDGER_RETENTION_MS } from '../src/cursor/ledgerRetention';
import {
    acknowledgeUploadedTraces,
    prepareTraceForUpload,
    requestIdForTurn,
    requestKey,
    uuidV7Timestamp,
} from '../src/cursor/requestIdentity';
import { resolveAutomaticTraceCutoff } from '../src/cursor/stateMigration';
import { aggregateTurnUsage, latestUsageModel } from '../src/cursor/usageAggregation';
import { mergePendingUsage } from '../src/cursor/usageQueue';
import { PendingUsage, RequestLedger, TraceData, TurnUsage } from '../src/interface';

const T0 = Date.parse('2026-01-10T19:57:00.000Z');
const ORIGINAL = '11111111-1111-4111-8111-111111111111';
const FORK = '22222222-2222-4222-8222-222222222222';

function trace(requestId: string, composerId: string, startMs = T0): TraceData {
    return {
        id: '',
        root_span_id: '',
        request_id: requestId,
        request_key: requestKey('cursor-tests', requestId),
        upload_kind: 'canonical',
        revision: 1,
        usage_key: '',
        cost_owner: true,
        name: 'cursor-chat',
        project_name: 'cursor-tests',
        start_time: new Date(startMs).toISOString(),
        end_time: new Date(startMs + 1000).toISOString(),
        turn_start_ms: startMs,
        turn_starts_ms: [startMs],
        input: { input: 'question' },
        output: { output: 'answer' },
        thread_id: composerId,
        metadata: {},
        spans: [{
            name: 'model-call',
            type: 'llm',
            startTime: new Date(startMs + 100),
            endTime: new Date(startMs + 200),
        }],
    };
}

function usage(model: string, chargedCents: number): TurnUsage {
    return {
        inputTokens: 10,
        outputTokens: 2,
        cacheReadTokens: 3,
        cacheWriteTokens: 0,
        chargedCents,
        requestCount: 1,
        models: [model],
    };
}

test('fork-first polling retains the original composer as owner', () => {
    const owners = resolveCanonicalBubbleOwners([
        {
            composerId: FORK,
            composerCreatedAt: T0 + 10_000,
            headers: [{ bubbleId: 'shared', type: 1, createdAt: new Date(T0 + 20_000).toISOString() }],
        },
        {
            composerId: ORIGINAL,
            composerCreatedAt: T0 - 1000,
            headers: [{ bubbleId: 'shared', type: 1, createdAt: new Date(T0).toISOString() }],
        },
    ]);
    const fork = trace('request', FORK, T0 + 20_000);
    fork.canonical_thread_id = owners.get('shared')?.composerId;
    fork.canonical_turn_start_ms = owners.get('shared')?.turnStartMs;

    const ledger: RequestLedger = {};
    const prepared = prepareTraceForUpload(fork, ledger);

    assert.equal(ledger[fork.request_key].ownerComposerId, ORIGINAL);
    assert.equal(ledger[fork.request_key].canonicalStatus, 'excluded');
    assert.equal(prepared?.upload_kind, 'fork_edit_revision');
    assert.equal(prepared?.cost_owner, true);
    assert.equal(uuidV7Timestamp(prepared!.id), T0);
});

test('an unedited inherited fork remains cost-excluded when seen first', () => {
    const fork = trace('request', FORK, T0);
    fork.canonical_thread_id = ORIGINAL;
    fork.canonical_turn_start_ms = T0;
    const prepared = prepareTraceForUpload(fork, {});
    assert.equal(prepared?.upload_kind, 'fork_copy');
    assert.equal(prepared?.cost_owner, false);
});

test('composer creation order keeps ownership stable after the source is edited', () => {
    const owners = resolveCanonicalBubbleOwners([
        {
            composerId: ORIGINAL,
            composerCreatedAt: T0,
            headers: [{ bubbleId: 'shared', type: 1, createdAt: new Date(T0 + 30_000).toISOString() }],
        },
        {
            composerId: FORK,
            composerCreatedAt: T0 + 10_000,
            headers: [{ bubbleId: 'shared', type: 1, createdAt: new Date(T0 + 5000).toISOString() }],
        },
    ]);
    assert.equal(owners.get('shared')?.composerId, ORIGINAL);
    assert.equal(owners.get('shared')?.turnStartMs, T0 + 5000);
});

test('same-timestamp edits receive distinct revision usage and child identities', () => {
    const ledger: RequestLedger = {};
    const original = prepareTraceForUpload(trace('same-time', ORIGINAL), ledger)!;
    acknowledgeUploadedTraces([original], ledger);
    const editedInput = trace('same-time', ORIGINAL);
    editedInput.input = { input: 'edited at the same timestamp' };
    const edited = prepareTraceForUpload(editedInput, ledger)!;
    assert.notEqual(edited.usage_key, original.usage_key);
    assert.notEqual(edited.spans![0].id, original.spans![0].id);
    assert.equal(edited.revision, 2);
});

test('independent legacy turns with identical text and timestamps stay distinct', () => {
    const left = requestIdForTurn({ userMessages: [{ id: 'left', text: 'same', resolvedTimestamp: T0 }] });
    const right = requestIdForTurn({ userMessages: [{ id: 'right', text: 'same', resolvedTimestamp: T0 }] });
    assert.notEqual(left, right);
});

test('legacy pending usage is upgraded instead of duplicated', () => {
    const legacy = {
        composerId: ORIGINAL,
        turnStartMs: T0,
        traceId: 'trace',
        spanId: 'span',
        projectName: 'cursor-tests',
        attempt: 2,
        nextAttemptAt: T0,
    } as PendingUsage;
    const modern = {
        usageKey: 'usage-key',
        requestId: 'request',
        requestKey: 'request-key',
        composerId: ORIGINAL,
        turnStartMs: T0,
        turnStartsMs: [T0],
        traceId: 'trace',
        spanId: 'span',
        projectName: 'cursor-tests',
    };
    const merged = mergePendingUsage([legacy], [modern], T0 + 5000);
    assert.equal(merged.length, 1);
    assert.equal(merged[0].usageKey, 'usage-key');
    assert.equal(merged[0].requestKey, 'request-key');
    assert.equal(merged[0].attempt, 2);
});

test('aggregated revisions keep the latest billed model for the root span', () => {
    const aggregate = aggregateTurnUsage([usage('old-model', 10), usage('new-model', 20)]);
    assert.equal(latestUsageModel(aggregate), 'new-model');
    assert.equal(aggregate.chargedCents, 30);
});

test('legacy cutoff migration prefers lastSyncTime before activation time', () => {
    assert.equal(resolveAutomaticTraceCutoff(undefined, T0, T0 + 1000), T0);
    assert.equal(resolveAutomaticTraceCutoff(T0 - 1000, T0, T0 + 1000), T0 - 1000);
});

test('composer ids are validated before SQL range construction', () => {
    assert.equal(isCursorComposerId(ORIGINAL), true);
    assert.equal(isCursorComposerId("x' OR 1=1 --"), false);
    assert.equal(composerIdFromKey(`composerData:${ORIGINAL}`), ORIGINAL);
    assert.equal(composerIdFromKey("composerData:x' OR 1=1 --"), undefined);
});

test('ledger compaction collapses revision costs and prunes expired deliveries', () => {
    const active = trace('active', ORIGINAL);
    const ledger: RequestLedger = {};
    prepareTraceForUpload(active, ledger);
    const entry = ledger[active.request_key];
    entry.canonicalStatus = 'uploaded';
    entry.usageStatus = 'complete';
    entry.usageByRevision = { first: usage('old-model', 10), edit: usage('new-model', 20) };
    // Reopening an old thread must not extend retention forever.
    entry.lastSeenAt = T0 + REQUEST_LEDGER_RETENTION_MS + 1;

    compactRequestLedger(ledger, T0 + REQUEST_LEDGER_RETENTION_MS - 1);
    assert.equal(Object.keys(entry.usageByRevision!).length, 1);
    assert.equal(Object.values(entry.usageByRevision!)[0].chargedCents, 30);

    compactRequestLedger(ledger, T0 + REQUEST_LEDGER_RETENTION_MS + 1);
    assert.equal(ledger[active.request_key], undefined);
});
