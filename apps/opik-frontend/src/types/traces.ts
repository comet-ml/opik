export enum FEEDBACK_SCORE_TYPE {
  sdk = "sdk",
  ui = "ui",
}

export interface TraceFeedbackScore {
  category_name?: string;
  reason?: string;
  name: string;
  source: FEEDBACK_SCORE_TYPE;
  value: number;
}

export interface UsageData {
  prompt_tokens: number;
  completion_tokens: number;
  total_tokens: number;
}

export interface BaseTraceDataErrorInfo {
  exception_type: string;
  message?: string;
  traceback: string;
}

export interface BaseTraceData {
  id: string;
  name: string;
  input: object;
  output: object;
  start_time: string;
  end_time: string;
  created_at: string;
  last_updated_at: string;
  metadata: object;
  feedback_scores?: TraceFeedbackScore[];
  tags: string[];
  usage?: UsageData;
  total_estimated_cost?: number;
  error_info?: BaseTraceDataErrorInfo;
}

export interface Trace extends BaseTraceData {
  project_id: string;
  workspace_name?: string;
}

export enum SPAN_TYPE {
  llm = "llm",
  general = "general",
  tool = "tool",
}

export interface Span extends BaseTraceData {
  type: SPAN_TYPE;
  parent_span_id: string;
  trace_id: string;
  project_id: string;
  workspace_name?: string;
}

export type BASE_TRACE_DATA_TYPE = SPAN_TYPE | "trace";

export interface AgentGraphData {
  format: "mermaid";
  data: string;
}
