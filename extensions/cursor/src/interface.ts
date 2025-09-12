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
    input: any;
    output: any;
    thread_id?: string;
    tags?: string[];
    usage?: {
        completion_tokens?: number;
        prompt_tokens?: number;
        total_tokens?: number;
    };
    metadata?: any;
}
