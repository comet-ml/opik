import { useCallback } from "react";
import useLocalStorageState from "use-local-storage-state";

import {
  useClearCreatedExperiments,
  useClearRunningMap,
  useResetDatasetFilters,
  useSetDatasetType,
  useSetDatasetVariables,
  useSetExperimentName,
  useSetSelectedRuleIds,
} from "@/store/PlaygroundStore";
import { PLAYGROUND_SELECTED_DATASET_VERSION_KEY } from "@/constants/llm";

/**
 * Clears everything the Playground keeps for a project except the prompts, so a project switch
 * does not carry one project's dataset, rules and experiments into another.
 */
function useResetPlaygroundSession() {
  const [, setDatasetVersionKey] = useLocalStorageState<string | null>(
    PLAYGROUND_SELECTED_DATASET_VERSION_KEY,
    { defaultValue: null },
  );
  const setSelectedRuleIds = useSetSelectedRuleIds();
  const clearCreatedExperiments = useClearCreatedExperiments();
  const clearRunningMap = useClearRunningMap();
  const resetDatasetFilters = useResetDatasetFilters();
  const setDatasetVariables = useSetDatasetVariables();
  const setExperimentName = useSetExperimentName();
  const setDatasetType = useSetDatasetType();

  return useCallback(() => {
    setDatasetVersionKey(null);
    setSelectedRuleIds(null);
    clearCreatedExperiments();
    clearRunningMap();
    resetDatasetFilters();
    setDatasetVariables([]);
    setExperimentName(null);
    setDatasetType(null);
  }, [
    setDatasetVersionKey,
    setSelectedRuleIds,
    clearCreatedExperiments,
    clearRunningMap,
    resetDatasetFilters,
    setDatasetVariables,
    setExperimentName,
    setDatasetType,
  ]);
}

export default useResetPlaygroundSession;
