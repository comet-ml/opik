// Metric selection uses a tri-state value:
//   null      -> all metrics selected (nothing persisted yet / "select all")
//   []        -> none selected
//   [..ids]   -> an explicit subset
// These helpers centralize the toggle transitions so the "all" (null) edge
// cases stay consistent between the UI and any callers reading the value.

export const toggleMetricSelection = (
  current: string[] | null,
  ruleId: string,
  allRuleIds: string[],
): string[] | null => {
  const total = allRuleIds.length;
  const isAllSelected = current === null || current.length === total;

  // From "all", toggling one off yields everything-but-that-one.
  if (isAllSelected) {
    const next = allRuleIds.filter((id) => id !== ruleId);
    return next.length > 0 ? next : [];
  }

  // Toggling an already-selected id off; empty result collapses to [].
  if (current.includes(ruleId)) {
    const next = current.filter((id) => id !== ruleId);
    return next.length > 0 ? next : [];
  }

  // Toggling a new id on; if that completes the full set, collapse to null ("all").
  const next = [...current, ruleId];
  return next.length === total ? null : next;
};

export const toggleAllMetrics = (isAllSelected: boolean): string[] | null =>
  isAllSelected ? [] : null;
