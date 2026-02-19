export interface ParamInfo {
  name: string;
  type: string;
}

export interface AgentInfo {
  name: string;
  project: string;
  params?: ParamInfo[];
}

export interface Runner {
  id: string;
  name: string;
  status: string;
  connected_at: string;
  agents: AgentInfo[];
}

export interface RunnerJob {
  id: string;
  runner_id: string;
  agent_name: string;
  status: string;
  inputs: object;
  result?: object;
  error?: string;
  stdout?: string;
  project: string;
  trace_id?: string;
  created_at: string;
  started_at?: string;
  completed_at?: string;
}

export interface PairResponse {
  pairing_code: string;
  runner_id: string;
  expires_in_seconds: number;
}

export interface CreateJobRequest {
  agent_name: string;
  inputs: object;
  project: string;
  runner_id: string;
}

export interface LogEntry {
  stream: "stdout" | "stderr";
  text: string;
}
