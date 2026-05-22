export type MetricRuleSelection = string[] | null;

export const getDefaultMetricRuleSelection = (
  selectAllMetricsByDefault: boolean,
): MetricRuleSelection => (selectAllMetricsByDefault ? null : []);

export const getMetricRuleSelectionOrDefault = (
  selection: MetricRuleSelection | undefined,
  defaultSelection: MetricRuleSelection,
): MetricRuleSelection =>
  selection !== undefined ? selection : defaultSelection;

export const toggleMetricRuleSelection = (
  ruleIds: string[],
  selectedRuleIds: MetricRuleSelection,
  ruleId: string,
  {
    useExplicitRuleIdsForAll = false,
  }: { useExplicitRuleIdsForAll?: boolean } = {},
): MetricRuleSelection => {
  const isAllSelected =
    selectedRuleIds === null || selectedRuleIds.length === ruleIds.length;

  if (isAllSelected) {
    const newSelection = ruleIds.filter((id) => id !== ruleId);
    return newSelection.length > 0 ? newSelection : [];
  }

  const isSelected = selectedRuleIds.includes(ruleId);

  if (isSelected) {
    const newSelection = selectedRuleIds.filter((id) => id !== ruleId);
    return newSelection.length > 0 ? newSelection : [];
  }

  const newSelection = [...selectedRuleIds, ruleId];

  if (newSelection.length !== ruleIds.length) {
    return newSelection;
  }

  return useExplicitRuleIdsForAll ? ruleIds : null;
};
