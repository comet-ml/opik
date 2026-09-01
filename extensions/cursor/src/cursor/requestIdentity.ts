import { createHash } from 'crypto';

import { ForkCopyState, RequestLedger, TraceData } from '../interface';

/** Build an RFC 9562 UUIDv7 whose embedded timestamp is the event time. */
export function deterministicUuidV7(timestampMs: number, identity: string): string {
    const timestamp = Math.max(0, Math.min(Math.trunc(timestampMs), 0xffffffffffff));
    const entropy = createHash('sha256').update(identity).digest();
    const bytes = Buffer.alloc(16);

    bytes.writeUIntBE(timestamp, 0, 6);
    entropy.copy(bytes, 6, 0, 10);
    bytes[6] = (bytes[6] & 0x0f) | 0x70;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;

    const hex = bytes.toString('hex');
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export function uuidV7Timestamp(uuid: string): number {
    return Number.parseInt(uuid.replace(/-/g, '').slice(0, 12), 16);
}

export function requestKey(projectName: string, requestId: string): string {
    return `${projectName}\u0000${requestId}`;
}

export function requestIdForTurn(group: { userMessages: any[] }): string {
    const cursorRequestId = group.userMessages.find(message =>
        typeof message.requestId === 'string' && message.requestId.trim()
    )?.requestId;

    if (cursorRequestId) {
        return cursorRequestId;
    }

    // Old Cursor versions did not persist requestId. The timestamp and prompt
    // survive a fork even though bubble ids do not, so this remains stable for
    // historical conversations without making identical prompts collide.
    const first = group.userMessages[0] ?? {};
    const parsedTimestamp = Date.parse(first.createdAt ?? '');
    const timestamp = first.resolvedTimestamp ?? (Number.isNaN(parsedTimestamp) ? 0 : parsedTimestamp);
    const content = group.userMessages
        .map(message => message.text || message.content || message.rawText || '')
        .join('\n\n');
    const digest = createHash('sha256')
        .update(`${timestamp}\u0000${content}`)
        .digest('hex');
    return `legacy-${digest}`;
}

export function traceFingerprint(trace: TraceData): string {
    return createHash('sha256')
        .update(JSON.stringify([trace.turn_start_ms, trace.input, trace.output]))
        .digest('hex');
}

function usageKey(trace: TraceData): string {
    return `${trace.request_key}\u0000${trace.thread_id}\u0000${trace.turn_start_ms}`;
}

function assignChildSpanIds(trace: TraceData): void {
    for (const [index, span] of (trace.spans ?? []).entries()) {
        span.id = deterministicUuidV7(
            span.startTime.getTime(),
            `cursor:child:${trace.id}:${trace.turn_start_ms}:${index}:${span.type}:${span.name}`
        );
    }
}

function decorateCanonical(
    trace: TraceData,
    revision: number,
    originalTurnStartMs: number,
    retry: boolean
): void {
    const isEdit = revision > 1;
    trace.upload_kind = isEdit ? 'edit_revision' : 'canonical';
    trace.revision = revision;
    trace.cost_owner = true;
    trace.usage_key = usageKey(trace);
    trace.tags = [
        ...(trace.tags ?? []),
        'cursor:canonical',
        ...(isEdit ? ['cursor:edit-revision'] : []),
        ...(retry ? ['cursor:retry'] : []),
    ];
    trace.metadata = {
        ...(trace.metadata ?? {}),
        request_id: trace.request_id,
        upload_kind: trace.upload_kind,
        cost_owner: true,
        revision,
        original_turn_start_ms: originalTurnStartMs,
        revision_turn_start_ms: trace.turn_start_ms,
        ...(retry ? { retry: true } : {}),
    };
    assignChildSpanIds(trace);
}

function decorateFork(
    trace: TraceData,
    copy: ForkCopyState,
    canonicalTraceId: string,
    copiedFromThreadId: string,
    retry: boolean
): void {
    const revision = copy.revision ?? 1;
    const isEdit = revision > 1;
    trace.upload_kind = isEdit ? 'fork_edit_revision' : 'fork_copy';
    trace.revision = revision;
    trace.cost_owner = isEdit;
    trace.usage_key = usageKey(trace);
    trace.tags = [
        ...(trace.tags ?? []),
        'cursor:fork-copy',
        ...(isEdit ? ['cursor:fork-edit', 'cursor:cost-owner'] : ['cursor:cost-excluded']),
        ...(retry ? ['cursor:retry'] : []),
    ];
    trace.metadata = {
        ...(trace.metadata ?? {}),
        request_id: trace.request_id,
        source_request_id: trace.request_id,
        canonical_trace_id: canonicalTraceId,
        copied_from_thread_id: copiedFromThreadId,
        upload_kind: trace.upload_kind,
        cost_owner: trace.cost_owner,
        revision,
        original_turn_start_ms: copy.originalTurnStartMs ?? copy.latestTurnStartMs ?? trace.turn_start_ms,
        revision_turn_start_ms: trace.turn_start_ms,
        ...(retry ? { retry: true } : {}),
    };
    assignChildSpanIds(trace);
}

/**
 * Classify a trace against the durable ledger and assign stable UUIDv7 ids.
 * Returns null for a request revision already delivered to the same UI thread.
 */
export function prepareTraceForUpload(trace: TraceData, ledger: RequestLedger): TraceData | null {
    const key = trace.request_key;
    const existing = ledger[key];
    const fingerprint = traceFingerprint(trace);
    const now = Date.now();

    if (!existing) {
        trace.id = deterministicUuidV7(
            trace.turn_start_ms,
            `cursor:trace:canonical:${trace.project_name}:${trace.request_id}`
        );
        trace.root_span_id = deterministicUuidV7(
            trace.turn_start_ms,
            `cursor:span:canonical:${trace.project_name}:${trace.request_id}`
        );
        decorateCanonical(trace, 1, trace.turn_start_ms, false);

        ledger[key] = {
            requestId: trace.request_id,
            projectName: trace.project_name ?? 'default',
            ownerComposerId: trace.thread_id!,
            turnStartMs: trace.turn_start_ms,
            latestTurnStartMs: trace.turn_start_ms,
            canonicalTraceId: trace.id,
            canonicalRootSpanId: trace.root_span_id,
            canonicalStatus: 'pending',
            canonicalFingerprint: fingerprint,
            canonicalRevision: 1,
            usageStatus: 'pending',
            usageByRevision: {},
            forkCopies: {},
            lastSeenAt: now,
        };
        return trace;
    }

    existing.lastSeenAt = now;
    existing.forkCopies ??= {};
    if (existing.ownerComposerId === trace.thread_id) {
        const lastTurnStart = existing.latestTurnStartMs ?? existing.turnStartMs;
        const changed = existing.canonicalFingerprint
            ? existing.canonicalFingerprint !== fingerprint
            : lastTurnStart !== trace.turn_start_ms;

        if (!changed && existing.canonicalStatus === 'uploaded') {
            // Populate fields introduced after the first request-ledger release.
            existing.canonicalFingerprint ??= fingerprint;
            existing.latestTurnStartMs ??= trace.turn_start_ms;
            existing.canonicalRevision ??= 1;
            existing.usageByRevision ??= {};
            return null;
        }

        if (changed) {
            existing.canonicalRevision = (existing.canonicalRevision ?? 1) + 1;
            existing.canonicalFingerprint = fingerprint;
            existing.latestTurnStartMs = trace.turn_start_ms;
            existing.canonicalStatus = 'pending';
            existing.usageStatus = 'pending';
        }

        trace.id = existing.canonicalTraceId;
        trace.root_span_id = existing.canonicalRootSpanId;
        // A logical request keeps its original UUIDv7 timestamp and table
        // position. The current revision time remains in turn_start_ms and in
        // metadata for usage attribution and auditability.
        trace.start_time = new Date(existing.turnStartMs).toISOString();
        decorateCanonical(
            trace,
            existing.canonicalRevision ?? 1,
            existing.turnStartMs,
            !changed
        );
        return trace;
    }

    const composerId = trace.thread_id!;
    let copy = existing.forkCopies[composerId];
    let changed = false;
    let created = false;
    if (!copy) {
        created = true;
        copy = {
            traceId: deterministicUuidV7(
                existing.turnStartMs,
                `cursor:trace:fork:${trace.project_name}:${trace.request_id}:${composerId}`
            ),
            rootSpanId: deterministicUuidV7(
                existing.turnStartMs,
                `cursor:span:fork:${trace.project_name}:${trace.request_id}:${composerId}`
            ),
            status: 'pending',
            fingerprint,
            latestTurnStartMs: trace.turn_start_ms,
            originalTurnStartMs: trace.turn_start_ms,
            revision: 1,
            usageStatus: 'disabled',
            usageByRevision: {},
        };
        existing.forkCopies[composerId] = copy;
    } else {
        const lastTurnStart = copy.latestTurnStartMs ?? existing.turnStartMs;
        changed = copy.fingerprint ? copy.fingerprint !== fingerprint : lastTurnStart !== trace.turn_start_ms;
        if (!changed && copy.status === 'uploaded') {
            copy.fingerprint ??= fingerprint;
            copy.latestTurnStartMs ??= trace.turn_start_ms;
            copy.revision ??= 1;
            copy.usageByRevision ??= {};
            return null;
        }
        if (changed) {
            copy.revision = (copy.revision ?? 1) + 1;
            copy.fingerprint = fingerprint;
            copy.latestTurnStartMs = trace.turn_start_ms;
            copy.status = 'pending';
            copy.usageStatus = 'pending';
        }
    }

    trace.id = copy.traceId;
    trace.root_span_id = copy.rootSpanId;
    trace.start_time = new Date(existing.turnStartMs).toISOString();
    decorateFork(
        trace,
        copy,
        existing.canonicalTraceId,
        existing.ownerComposerId,
        !created && !changed && copy.status === 'pending'
    );
    return trace;
}

export function acknowledgeUploadedTraces(traces: TraceData[], ledger: RequestLedger): void {
    for (const trace of traces) {
        const entry = ledger[trace.request_key];
        if (!entry) {
            continue;
        }
        if (trace.upload_kind === 'canonical' || trace.upload_kind === 'edit_revision') {
            entry.canonicalStatus = 'uploaded';
        } else if (trace.thread_id && entry.forkCopies[trace.thread_id]) {
            entry.forkCopies[trace.thread_id].status = 'uploaded';
        }
        entry.lastSeenAt = Date.now();
    }
}
