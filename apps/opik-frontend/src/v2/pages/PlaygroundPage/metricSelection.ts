// Metric selection is a plain list of rule ids. Both an empty list and null mean nothing is
// selected -- the store still writes null whenever a dataset has no stored selection -- and the
// run is then scored only by the rules that target experiments.

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
