import { UsageData } from "@/types/shared";
import { CommentItems } from "./comment";
import { GuardrailValidation } from "./guardrails";
import { ThreadStatus } from "./thread";

export enum USER_FEEDBACK_SCORE {
  dislike,
  like,
}

export enum FEEDBACK_SCORE_TYPE {
  sdk = "sdk",
  ui = "ui",
  online_scoring = "online_scoring",
}

export enum TRACE_VISIBILITY_MODE {
  default = "default",
  hidden = "hidden",
}

export type FeedbackScoreValueByAuthorMap = Record<
  string,
  {
    value: number;
    reason?: string;
    category_name?: string;
    source: FEEDBACK_SCORE_TYPE;
    last_updated_at: string;
    span_type?: string;
    span_id?: string;
  }
>;

export type TraceFeedbackScore = {
  category_name?: string;
  reason?: string;
  name: string;
  source: FEEDBACK_SCORE_TYPE;
  created_by?: string;
  last_updated_by?: string;
  last_updated_at?: string;
  value: number;
  value_by_author?: FeedbackScoreValueByAuthorMap;
};

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
  llm_span_count?: number;
  has_tool_spans?: boolean;
  thread_id?: string;
  project_id: string;
  workspace_name?: string;
  visibility_mode?: TRACE_VISIBILITY_MODE;
  span_feedback_scores?: TraceFeedbackScore[];
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
  model?: string;
  provider?: string;
}

export type BASE_TRACE_DATA_TYPE = SPAN_TYPE | "trace";

export interface AgentGraphData {
  format: "mermaid";
  data: string;
}

export interface Thread {
  id: string;
  thread_model_id: string;
  project_id: string;
  start_time: string;
  end_time: string;
  duration: number;
  first_message: object;
  last_message: object;
  number_of_messages: number;
  usage?: UsageData;
  total_estimated_cost?: number;
  last_updated_at: string;
  created_by: string;
  created_at: string;
  status: ThreadStatus;
  feedback_scores?: TraceFeedbackScore[];
  comments?: CommentItems;
  tags?: string[];
}
