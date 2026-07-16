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
 * Returns the extracted name or null if no name can be extracted.
 */
export const extractMetricNameFromPythonCode = (
  code: string,
): string | null => {
  if (!code) return null;
  // Mirror the backend's static name extraction
  // (opik-python-backend process_worker._metric_name_ast) so a name derived here
  // — e.g. for the Optimization Studio create-time `objective_name` — matches the
  // name the metric actually scores under. Otherwise the UI keys feedback scores
  // by the wrong name and shows "-". Tried in the same precedence order:

  // 1. Name passed to the base constructor: super().__init__(name="metric_name").
  //    The dominant idiom, and the one the backend AST prefers first.
  const superCtor = code.match(
    /super\(\)\s*\.\s*__init__\s*\([^)]*\bname\s*=\s*["']([^"']+)["']/,
  );
  if (superCtor) return superCtor[1];

  // 2. Default of __init__'s `name` param: def __init__(self, name="metric_name").
  const initDefault = code.match(
    /def\s+__init__\s*\([^)]*\bname(?:\s*:\s*[^=,)]+)?\s*=\s*["']([^"']+)["']/,
  );
  if (initDefault) return initDefault[1];

  // 3. Class-level attribute: a line-leading `name = "metric_name"` (guarded so
  //    it does not match `self.name = ...` or other attribute assignments).
  const classAttr = code.match(/(?:^|\n)[ \t]*name\s*=\s*["']([^"']+)["']/);
  if (classAttr) return classAttr[1];

  return null;
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
    const metricName = extractMetricNameFromPythonCode(
      pythonRule.code.metric || "",
    );
    return metricName ? [metricName] : [];
  }
  return [];
};
