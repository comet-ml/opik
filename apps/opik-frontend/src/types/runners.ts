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
  debug_session_id?: string;
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
  runner_id?: string;
  debug?: boolean;
}

export interface LogEntry {
  stream: "stdout" | "stderr";
  text: string;
}

export interface DebugSession {
  session_id: string;
  trace_id: string;
  cursor: number;
  total_nodes: number;
  status: string;
  current_node: DebugGraphNode | null;
  last_span_id: string;
  last_node: DebugGraphNode | null;
}

export interface DebugGraphNode {
  node_id: string;
  function_name: string;
  qualified_name: string;
  module_path: string;
  inputs: object;
  output: unknown;
  span_id: string;
  trace_id: string;
  parent_span_id: string | null;
  call_order: number;
}

export interface DebugGraph {
  session_id: string;
  nodes: DebugGraphNode[];
}
