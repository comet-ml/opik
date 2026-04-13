export interface SandboxPairCode {
  pair_code: string;
  runner_id: string;
  expires_in_seconds: number;
  created_at: number;
}

/**
 * Backend returns: PAIRING, CONNECTED, DISCONNECTED.
 * Client-only states: LOADING (initial fetch), IDLE (no code requested), EXPIRED (code timed out).
 */
export enum RunnerConnectionStatus {
  LOADING = "loading",
  IDLE = "idle",
  PAIRING = "pairing",
  EXPIRED = "expired",
  CONNECTED = "connected",
  DISCONNECTED = "disconnected",
}

export interface LocalRunnerAgent {
  name: string;
  description?: string;
  language?: string;
  params?: { name: string; type: string }[];
}

export interface LocalRunner {
  id: string;
  name?: string;
  project_id: string;
  status: RunnerConnectionStatus;
  connected_at?: string;
  agents?: LocalRunnerAgent[];
}

export enum SandboxJobStatus {
  PENDING = "pending",
  RUNNING = "running",
  COMPLETED = "completed",
  FAILED = "failed",
  CANCELLED = "cancelled",
}

export interface SandboxJob {
  id: string;
  status: SandboxJobStatus;
  agent_name: string;
  inputs: Record<string, unknown>;
  result?: unknown;
  error?: string;
  trace_id?: string;
  created_at: string;
  completed_at?: string;
}

export interface CreateLocalRunnerJobRequest {
  agent_name: string;
  inputs: Record<string, unknown>;
  project_id: string;
  mask_id?: string;
  blueprint_name?: string;
}
