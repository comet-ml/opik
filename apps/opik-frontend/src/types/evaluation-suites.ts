export enum ExperimentItemStatus {
  PASSED = "passed",
  FAILED = "failed",
  SKIPPED = "skipped",
}

export interface ExecutionPolicy {
  runs_per_item: number;
  pass_threshold: number;
}

export const MAX_RUNS_PER_ITEM = 100;

export const DEFAULT_EXECUTION_POLICY: ExecutionPolicy = {
  runs_per_item: 1,
  pass_threshold: 1,
};

export enum MetricType {
  LLM_AS_JUDGE = "llm_judge",
  CONTAINS = "contains",
  EQUALS = "equals",
  LEVENSHTEIN_RATIO = "levenshtein_ratio",
  HALLUCINATION = "hallucination",
  MODERATION = "moderation",
}

export const METRIC_TYPE_LABELS: Record<MetricType, string> = {
  [MetricType.LLM_AS_JUDGE]: "LLM as a Judge",
  [MetricType.CONTAINS]: "Contains",
  [MetricType.EQUALS]: "Equals",
  [MetricType.LEVENSHTEIN_RATIO]: "Levenshtein Ratio",
  [MetricType.HALLUCINATION]: "Hallucination",
  [MetricType.MODERATION]: "Moderation",
};

export interface StringMatchConfig {
  value: string;
  case_sensitive: boolean;
}

export interface ThresholdConfig {
  threshold: number;
}

export interface LLMJudgeConfig {
  assertions: string[];
}

export type MetricConfig = StringMatchConfig | ThresholdConfig | LLMJudgeConfig;

export interface EvaluatorDisplayRow {
  id: string;
  evaluatorId?: string;
  name: string;
  type: MetricType;
  config: MetricConfig;
  isNew?: boolean;
  isEdited?: boolean;
}
