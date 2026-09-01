import { CursorSession } from './cursor/interface';

export interface SessionInfo {
    lastUploadId?: string;
    lastUploadTime?: number;
}
  
export interface Session {
    id: string;
    basePath: string;
    lastUploadId?: string;
    lastUploadDate?: string;
    lastUploadTime?: number;
    cursorSession?: CursorSession;
}

export interface SpanData {
    id?: string;
    name: string;
    type: 'llm' | 'tool' | 'general';
    startTime: Date;
    endTime: Date;
    model?: string;
    provider?: string;
    input?: any;
    output?: any;
    errorInfo?: { exceptionType: string; message: string; traceback: string };
    metadata?: Record<string, unknown>;
    tags?: string[];
}

export interface TraceData {
    id: string;
    root_span_id: string;
    request_id: string;
    request_key: string;
    upload_kind: 'canonical' | 'fork_copy' | 'edit_revision' | 'fork_edit_revision';
    revision: number;
    usage_key: string;
    cost_owner: boolean;
    name: string;
    project_name?: string;
    start_time: string; // ISO 8601 format
    end_time?: string; // ISO 8601 format
    turn_start_ms: number;
    turn_starts_ms: number[];
    canonical_thread_id?: string;
    canonical_turn_start_ms?: number;
    model?: string;
    input: any;
    output: any;
    thread_id?: string;
    tags?: string[];
    metadata?: any;
    spans?: SpanData[];
}

export interface TurnUsage {
    inputTokens: number;
    outputTokens: number;
    cacheReadTokens: number;
    cacheWriteTokens: number;
    chargedCents: number;
    requestCount: number;
    models: string[];
}

export interface PendingUsage {
    usageKey: string;
    requestId: string;
    requestKey: string;
    composerId: string;
    turnStartMs: number;
    turnEndMs?: number;
    turnStartsMs: number[];
    traceId: string;
    spanId: string;
    projectName: string;
    attempt: number;
    nextAttemptAt: number;
}

export type UploadStatus = 'pending' | 'uploaded' | 'excluded';
export type UsageStatus = 'disabled' | 'pending' | 'complete';

export interface ForkCopyState {
    traceId: string;
    rootSpanId: string;
    status: UploadStatus;
    fingerprint?: string;
    latestTurnStartMs?: number;
    originalTurnStartMs?: number;
    revision?: number;
    usageStatus?: UsageStatus;
    usageByRevision?: Record<string, TurnUsage>;
    appliedUsageKeys?: string[];
}

/**
 * Durable identity and delivery state for one logical Cursor request.
 *
 * The request key includes the Opik project. Cursor's requestId is stable when
 * a composer is forked, while bubble and composer ids are not.
 */
export interface RequestLedgerEntry {
    requestId: string;
    projectName: string;
    ownerComposerId: string;
    turnStartMs: number;
    canonicalTraceId: string;
    canonicalRootSpanId: string;
    canonicalStatus: UploadStatus;
    usageStatus: UsageStatus;
    canonicalFingerprint?: string;
    latestTurnStartMs?: number;
    canonicalRevision?: number;
    usageByRevision?: Record<string, TurnUsage>;
    appliedUsageKeys?: string[];
    forkCopies: Record<string, ForkCopyState>;
    lastSeenAt: number;
}

export type RequestLedger = Record<string, RequestLedgerEntry>;
