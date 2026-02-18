export enum ExperimentItemStatus {
  PASSED = "passed",
  FAILED = "failed",
  SKIPPED = "skipped",
}

export interface BehaviorResult {
  behavior_name: string;
  passed: boolean;
  pass_score?: number;
  reason?: string;
}

export interface ExecutionPolicy {
  runs_per_item: number;
  pass_threshold: number;
}

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

interface BaseEvaluator {
  id: string;
  name: string;
  metric_type: MetricType;
  metric_config: MetricConfig;
  created_at: string;
  created_by: string;
  last_updated_at: string;
  last_updated_by: string;
}

export interface DatasetEvaluator extends BaseEvaluator {
  dataset_id: string;
}

export interface DatasetItemEvaluator extends BaseEvaluator {
  dataset_item_id: string;
}

export interface BehaviorDisplayRow {
  id: string;
  evaluatorId?: string;
  name: string;
  metric_type: MetricType;
  metric_config: MetricConfig;
  isNew?: boolean;
  isEdited?: boolean;
}

export function generateBehaviorName(
  metricType: MetricType,
  config: MetricConfig,
): string {
  switch (metricType) {
    case MetricType.LLM_AS_JUDGE: {
      const assertion = (config as LLMJudgeConfig).assertions?.[0] ?? "";
      return assertion.length > 50
        ? assertion.slice(0, 50) + "\u2026"
        : assertion;
    }
    case MetricType.CONTAINS:
      return `Contains: "${(config as StringMatchConfig).value}"`;
    case MetricType.EQUALS:
      return `Equals: "${(config as StringMatchConfig).value}"`;
    case MetricType.LEVENSHTEIN_RATIO:
      return `Levenshtein >= ${(config as ThresholdConfig).threshold}`;
    case MetricType.HALLUCINATION:
      return `Hallucination >= ${(config as ThresholdConfig).threshold}`;
    case MetricType.MODERATION:
      return `Moderation >= ${(config as ThresholdConfig).threshold}`;
    default:
      return metricType;
  }
}
