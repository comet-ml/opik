import { LLM_SCHEMA_TYPE, ProviderMessageType } from "@/types/llm";

export enum EVALUATORS_RULE_TYPE {
  llm_judge = "llm_as_judge", // TODO lala
  python_code = "python_code",
}

export interface LLMJudgeModel {
  name: string;
  temperature: number;
}

export interface LLMJudgeSchema {
  name: string;
  type: LLM_SCHEMA_TYPE;
  description: string;
}

export interface LLMJudgeObject {
  model: LLMJudgeModel;
  messages: ProviderMessageType[];
  variables: Record<string, string>;
  schema: LLMJudgeSchema[];
}

export interface LLMJudgeDetails {
  type: EVALUATORS_RULE_TYPE.llm_judge;
  code: LLMJudgeObject; // TODO lala
}

export interface PythonCodeObject {
  code: string;
}

export interface PythonCodeDetails {
  type: EVALUATORS_RULE_TYPE.python_code;
  code: PythonCodeObject; // TODO lala
}

export type EvaluatorsRule = {
  id: string;
  name: string;
  projectId: string; // TODO lala
  samplingRate: number;
  created_at: string;
  created_by: string;
  last_updated_at: string;
  last_updated_by: string;
} & (LLMJudgeDetails | PythonCodeDetails);
