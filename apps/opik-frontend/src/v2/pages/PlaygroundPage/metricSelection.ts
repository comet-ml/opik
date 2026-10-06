import {
  EVAL_TRIGGER_SCOPE,
  EVALUATORS_RULE_SCOPE,
  EvaluatorsRule,
} from "@/types/automations";
import { getUIRuleScope } from "@/v2/pages-shared/automations/AddEditRuleDialog/helpers";

export const isTraceRule = (rule: EvaluatorsRule): boolean =>
  getUIRuleScope(rule.type) === EVALUATORS_RULE_SCOPE.trace;

// An enabled rule targeting experiments scores every dataset run whether or not it is picked.
// Mirrors the trigger-scope branch of OnlineScoringSampler.shouldScoreTrace on the backend.
export const isAlwaysRunRule = (rule: EvaluatorsRule): boolean =>
  rule.enabled !== false &&
  (rule.trigger_scope === EVAL_TRIGGER_SCOPE.experiment ||
    rule.trigger_scope === EVAL_TRIGGER_SCOPE.both);

// A selection is a plain list of rule ids. null is read as empty because the store still writes
// it whenever a dataset has no stored selection.
export const toggleMetricSelection = (
  current: string[] | null,
  ruleId: string,
): string[] => {
  const selected = current ?? [];

  return selected.includes(ruleId)
    ? selected.filter((id) => id !== ruleId)
    : [...selected, ruleId];
};

export const toggleAllMetrics = (
  isAllSelected: boolean,
  allRuleIds: string[],
): string[] => (isAllSelected ? [] : allRuleIds);
