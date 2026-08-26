// Metric selection is a plain list of rule ids. An empty list -- or a legacy null persisted by
// an older session -- means nothing is selected: the run is then scored by the rules that target
// experiments, and by nothing else.

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
