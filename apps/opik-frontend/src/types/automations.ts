import { LLMJudgeSchema, ProviderMessageType } from "@/types/llm";
import { PROVIDER_MODEL_TYPE } from "@/types/providers";
import { Filters } from "@/types/filters";

export enum EVALUATORS_RULE_TYPE {
  llm_judge = "llm_as_judge",
  python_code = "user_defined_metric_python",
  thread_llm_judge = "trace_thread_llm_as_judge",
  thread_python_code = "trace_thread_user_defined_metric_python",
  span_llm_judge = "span_llm_as_judge",
  span_python_code = "span_user_defined_metric_python",
}

export enum EVALUATORS_RULE_SCOPE {
  trace = "trace",
  thread = "thread",
  span = "span",
}

export enum UI_EVALUATORS_RULE_TYPE {
  llm_judge = "llm_judge",
  python_code = "python_code",
}

export interface LLMJudgeModel {
  name: PROVIDER_MODEL_TYPE;
  temperature: number;
  seed?: number | null;
  custom_parameters?: Record<string, unknown> | null;
  throttling?: number;
  max_concurrent_requests?: number;
}

export interface LLMJudgeObject {
  model: LLMJudgeModel;
  messages: ProviderMessageType[];
  variables?: Record<string, string>;
  schema: LLMJudgeSchema[];
}

export interface LLMJudgeDetails {
  type:
    | EVALUATORS_RULE_TYPE.llm_judge
    | EVALUATORS_RULE_TYPE.thread_llm_judge
    | EVALUATORS_RULE_TYPE.span_llm_judge;
  code: LLMJudgeObject;
}

export interface PythonCodeDetailsTraceForm {
  metric: string;
  arguments: Record<string, string>;
  parsingArgumentsError?: boolean;
}

export interface PythonCodeDetailsThreadForm {
  metric: string;
}

export interface PythonCodeDetailsSpanForm {
  metric: string;
  arguments: Record<string, string>;
  parsingArgumentsError?: boolean;
}

export type PythonCodeObject =
  | PythonCodeDetailsTraceForm
  | PythonCodeDetailsThreadForm
  | PythonCodeDetailsSpanForm;

export interface PythonCodeDetails {
  type:
    | EVALUATORS_RULE_TYPE.python_code
    | EVALUATORS_RULE_TYPE.thread_python_code
    | EVALUATORS_RULE_TYPE.span_python_code;
  code: PythonCodeObject;
}

export interface ProjectReference {
  project_id: string;
  project_name: string;
}

export type EvaluatorsRule = {
  id: string;
  name: string;
  project_ids?: string[]; // For WRITE operations (CREATE/UPDATE) - just IDs
  projects?: ProjectReference[]; // For READ operations (GET responses) - ID + name pairs, sorted alphabetically by name
  project_id?: string; // Legacy field for backward compatibility (first project's ID)
  project_name?: string; // Legacy field for backward compatibility (first project's name)
  sampling_rate: number;
  enabled?: boolean;
  filters?: Filters;
  created_at: string;
  created_by: string;
  last_updated_at: string;
  last_updated_by: string;
} & (LLMJudgeDetails | PythonCodeDetails);

export enum EVALUATOR_LOG_LEVEL {
  INFO = "INFO",
  ERROR = "ERROR",
  WARN = "WARN",
  TRACE = "TRACE",
  DEBUG = "DEBUG",
}

export interface EvaluatorRuleLogItem {
  timestamp: string;
  level: EVALUATOR_LOG_LEVEL;
  message: string;
  markers?: Record<string, string>;
}

export interface EvaluatorRuleLogItemWithId extends EvaluatorRuleLogItem {
  id: string;
}
