export enum RunnerConnectionStatus {
  IDLE = "idle",
  CONNECTED = "connected",
  DISCONNECTED = "disconnected",
}

export interface LocalRunnerAgent {
  name: string;
  description?: string;
  language?: string;
  params?: { name: string; type: string; presence?: "required" | "optional" }[];
}

export type LocalRunnerType = "connect" | "endpoint";

export interface LocalRunner {
  id: string;
  name?: string;
  project_id: string;
  status: RunnerConnectionStatus;
  type?: LocalRunnerType;
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
