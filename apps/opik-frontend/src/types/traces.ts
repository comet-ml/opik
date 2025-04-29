import { UsageData } from "@/types/shared";
import { CommentItems } from "./comment";
import { GuardrailValidation } from "./guardrails";

export enum USER_FEEDBACK_SCORE {
  dislike,
  like,
}

export enum FEEDBACK_SCORE_TYPE {
  sdk = "sdk",
  ui = "ui",
  online_scoring = "online_scoring",
}

export interface TraceFeedbackScore {
  category_name?: string;
  reason?: string;
  name: string;
  source: FEEDBACK_SCORE_TYPE;
  value: number;
  last_updated_by?: string;
  last_updated_at?: string;
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
  duration: number;
  created_at: string;
  last_updated_at: string;
  metadata: object;
  feedback_scores?: TraceFeedbackScore[];
  comments: CommentItems;
  tags: string[];
  usage?: UsageData;
  total_estimated_cost?: number;
  error_info?: BaseTraceDataErrorInfo;
  guardrails_validations?: GuardrailValidation[];
}

export interface Trace extends BaseTraceData {
  span_count?: number;
  thread_id?: string;
  project_id: string;
  workspace_name?: string;
}

export enum SPAN_TYPE {
  llm = "llm",
  general = "general",
  tool = "tool",
  guardrail = "guardrail",
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

export interface Thread {
  id: string;
  project_id: string;
  start_time: string;
  end_time: string;
  duration: number;
  first_message: object;
  last_message: object;
  number_of_messages: number;
  last_updated_at: string;
  created_by: string;
  created_at: string;
}
