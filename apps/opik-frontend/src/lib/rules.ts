import {
  EvaluatorsRule,
  EVALUATORS_RULE_TYPE,
  LLMJudgeDetails,
  PythonCodeDetails,
} from "@/types/automations";
import { LLMJudgeSchema } from "@/types/llm";

export const isPythonCodeRule = (
  rule: EvaluatorsRule,
): rule is EvaluatorsRule & PythonCodeDetails => {
  return (
    rule.type === EVALUATORS_RULE_TYPE.python_code ||
    rule.type === EVALUATORS_RULE_TYPE.thread_python_code ||
    rule.type === EVALUATORS_RULE_TYPE.span_python_code
  );
};

/**
 * Attempts to extract metric name from Python code by parsing the __init__ default parameter.
 * Looks for patterns like: def __init__(self, name: str = "my_custom_metric")
 * Returns the extracted name or null if no name can be extracted.
 */
export const extractMetricNameFromPythonCode = (
  code: string,
): string | null => {
  // Match: def __init__(self, name: str = "metric_name") or name = 'metric_name'
  const pattern =
    /def\s+__init__\s*\([^)]*name(?:\s*:\s*str)?\s*=\s*["']([^"']+)["']/;
  const match = code.match(pattern);
  return match?.[1] || null;
};

export const isLLMJudgeRule = (
  rule: EvaluatorsRule,
): rule is EvaluatorsRule & LLMJudgeDetails => {
  return (
    rule.type === EVALUATORS_RULE_TYPE.llm_judge ||
    rule.type === EVALUATORS_RULE_TYPE.thread_llm_judge ||
    rule.type === EVALUATORS_RULE_TYPE.span_llm_judge
  );
};

export const getScoreNamesFromRule = (rule: EvaluatorsRule): string[] => {
  if (isLLMJudgeRule(rule)) {
    return rule.code.schema?.map((s: LLMJudgeSchema) => s.name) || [];
  }
  if (isPythonCodeRule(rule)) {
    const metricName = extractMetricNameFromPythonCode(rule.code.metric || "");
    return metricName ? [metricName] : [];
  }
  return [];
};
