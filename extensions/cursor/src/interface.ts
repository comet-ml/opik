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

export interface TraceData {
    name: string;
    project_name?: string;
    start_time: string; // ISO 8601 format
    end_time?: string; // ISO 8601 format
    turn_start_ms: number;
    model?: string;
    input: any;
    output: any;
    thread_id?: string;
    tags?: string[];
    metadata?: any;
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
    composerId: string;
    turnStartMs: number;
    traceId: string;
    spanId: string;
    projectName: string;
    attempt: number;
    nextAttemptAt: number;
}
