import {
  EvaluatorsRule,
  EVALUATORS_RULE_TYPE,
  LLMJudgeDetails,
  PythonCodeDetails,
} from "@/types/automations";
import { LLMJudgeSchema } from "@/types/llm";

export const isPythonCodeRule = (rule: EvaluatorsRule): boolean => {
  return (
    rule.type === EVALUATORS_RULE_TYPE.python_code ||
    rule.type === EVALUATORS_RULE_TYPE.thread_python_code ||
    rule.type === EVALUATORS_RULE_TYPE.span_python_code
  );
};

/**
 * Attempts to extract metric name from Python code by parsing the __init__ default parameter.
 * Looks for patterns like: def __init__(self, name: str = "my_custom_metric")
 * Returns empty array if no name can be extracted.
 */
const extractMetricNameFromPythonCode = (code: string): string[] => {
  // Match: def __init__(self, name: str = "metric_name") or name = 'metric_name'
  const pattern =
    /def\s+__init__\s*\([^)]*name(?:\s*:\s*str)?\s*=\s*["']([^"']+)["']/;
  const match = code.match(pattern);
  return match?.[1] ? [match[1]] : [];
};

export const isLLMJudgeRule = (rule: EvaluatorsRule): boolean => {
  return (
    rule.type === EVALUATORS_RULE_TYPE.llm_judge ||
    rule.type === EVALUATORS_RULE_TYPE.thread_llm_judge ||
    rule.type === EVALUATORS_RULE_TYPE.span_llm_judge
  );
};

export const getScoreNamesFromRule = (rule: EvaluatorsRule): string[] => {
  if (isLLMJudgeRule(rule)) {
    const llmRule = rule as EvaluatorsRule & LLMJudgeDetails;
    return llmRule.code.schema?.map((s: LLMJudgeSchema) => s.name) || [];
  }
  if (isPythonCodeRule(rule)) {
    const pythonRule = rule as EvaluatorsRule & PythonCodeDetails;
    // Attempt to extract metric name from Python code by parsing __init__ default parameter
    // This works for the common pattern: def __init__(self, name: str = "metric_name")
    // Falls back to empty array if name cannot be extracted (dynamic names, etc.)
    return extractMetricNameFromPythonCode(pythonRule.code.metric || "");
  }
  return [];
};
