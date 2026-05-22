export type MetricRuleSelection = string[] | null;

export const getResolvedMetricRuleSelectionDefault = (
  ruleIds: string[],
  selectAllMetricsByDefault: boolean,
): MetricRuleSelection | undefined => {
  if (!selectAllMetricsByDefault) {
    return [];
  }

  return ruleIds.length > 0 ? ruleIds : undefined;
};

export const getDatasetMetricRuleSelectionUpdate = ({
  datasetId,
  trackedDatasetId,
  selectedRuleIds,
  ruleIds,
  selectAllMetricsByDefault,
}: {
  datasetId: string | null;
  trackedDatasetId: string | null | undefined;
  selectedRuleIds: MetricRuleSelection;
  ruleIds: string[];
  selectAllMetricsByDefault: boolean;
}): {
  trackedDatasetId: string | null | undefined;
  nextSelectedRuleIds?: MetricRuleSelection;
} => {
  if (!datasetId) {
    if (trackedDatasetId === undefined) {
      return { trackedDatasetId };
    }

    return {
      trackedDatasetId: null,
      nextSelectedRuleIds: selectedRuleIds === null ? undefined : null,
    };
  }

  const isInitialHydration = trackedDatasetId === undefined;
  const isDatasetChanged =
    !isInitialHydration && trackedDatasetId !== datasetId;
  const hasExplicitSelection = selectedRuleIds !== null;
  const hasLoadedRules = ruleIds.length > 0;

  if (isInitialHydration && hasExplicitSelection) {
    if (!hasLoadedRules) {
      return { trackedDatasetId };
    }

    const ruleIdsSet = new Set(ruleIds);
    const isSelectionForCurrentRules = selectedRuleIds.every((ruleId) =>
      ruleIdsSet.has(ruleId),
    );

    if (isSelectionForCurrentRules) {
      return { trackedDatasetId: datasetId };
    }
  }

  if (!isInitialHydration && !isDatasetChanged && hasExplicitSelection) {
    return { trackedDatasetId: datasetId };
  }

  const defaultRuleIds = getResolvedMetricRuleSelectionDefault(
    ruleIds,
    selectAllMetricsByDefault,
  );

  if (defaultRuleIds !== undefined) {
    return {
      trackedDatasetId: datasetId,
      nextSelectedRuleIds: defaultRuleIds,
    };
  }

  if (hasExplicitSelection) {
    return {
      trackedDatasetId,
      nextSelectedRuleIds: null,
    };
  }

  return { trackedDatasetId };
};

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
