import {
  EvaluatorsRule,
  EVALUATORS_RULE_TYPE,
  LLMJudgeDetails,
} from "@/types/automations";
import { LLMJudgeSchema } from "@/types/llm";

export const isPythonCodeRule = (rule: EvaluatorsRule): boolean => {
  return (
    rule.type === EVALUATORS_RULE_TYPE.python_code ||
    rule.type === EVALUATORS_RULE_TYPE.thread_python_code ||
    rule.type === EVALUATORS_RULE_TYPE.span_python_code
  );
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
    // Python code metric contains the full code snippet, not the score name.
    // The actual score name is determined at runtime from ScoreResult.name,
    // so we cannot extract it statically. Return empty array to avoid
    // incorrect polling for a score with the code string as its name.
    return [];
  }
  return [];
};
