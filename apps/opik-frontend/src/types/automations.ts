import { LLM_SCHEMA_TYPE, ProviderMessageType } from "@/types/llm";
import { PROVIDER_MODEL_TYPE } from "@/types/providers";

export enum EVALUATORS_RULE_TYPE {
  llm_judge = "llm_as_judge",
  python_code = "user_defined_metric_python",
}

export interface LLMJudgeModel {
  name: PROVIDER_MODEL_TYPE;
  temperature: number;
}

export interface LLMJudgeSchema {
  name: string;
  type: LLM_SCHEMA_TYPE;
  description: string;
  unsaved: boolean;
}

export interface LLMJudgeObject {
  model: LLMJudgeModel;
  messages: ProviderMessageType[];
  variables: Record<string, string>;
  schema: LLMJudgeSchema[];
}

export interface LLMJudgeDetails {
  type: EVALUATORS_RULE_TYPE.llm_judge;
  code: LLMJudgeObject;
}

export interface PythonCodeObject {
  metric: string;
  arguments: Record<string, string>;
}

export interface PythonCodeDetails {
  type: EVALUATORS_RULE_TYPE.python_code;
  code: PythonCodeObject;
}

export type EvaluatorsRule = {
  id: string;
  name: string;
  project_id: string;
  project_name?: string;
  sampling_rate: number;
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
}
