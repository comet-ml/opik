import {
  Dispatch,
  SetStateAction,
  useCallback,
  useEffect,
  useMemo,
} from "react";
import {
  RowPinningState,
  OnChangeFn,
  functionalUpdate,
} from "@tanstack/react-table";
import { keepPreviousData } from "@tanstack/react-query";

import useExperimentsList from "@/api/datasets/useExperimentsList";
import {
  EXPERIMENTS_LIST_POLLING_INTERVAL_MS,
  GroupedExperiment,
} from "@/hooks/useGroupedExperimentsList";
import { PinningConfig } from "@/shared/DataTable/DataTable";

const MAX_PINNED_FETCH_SIZE = 100;

type UseExperimentsPinningParams = {
  rows: GroupedExperiment[];
  workspaceName: string;
  projectId?: string;
  pinnedIds: string[];
  setPinnedIds: Dispatch<SetStateAction<string[]>>;
  enabled: boolean;
  polling?: boolean;
};

const useExperimentsPinning = ({
  rows,
  workspaceName,
  projectId,
  pinnedIds,
  setPinnedIds,
  enabled,
  polling = false,
}: UseExperimentsPinningParams) => {
  const missingIds = useMemo(() => {
    const loadedIds = new Set(rows.map((row) => row.id));
    return pinnedIds.filter((id) => !loadedIds.has(id));
  }, [rows, pinnedIds]);

  const { data, isPlaceholderData, refetch } = useExperimentsList(
    {
      workspaceName,
      projectId,
      experimentIds: missingIds,
      page: 1,
      size: MAX_PINNED_FETCH_SIZE,
    },
    {
      enabled: enabled && missingIds.length > 0,
      placeholderData: keepPreviousData,
      refetchInterval: polling ? EXPERIMENTS_LIST_POLLING_INTERVAL_MS : false,
    },
  );

  const pinnedExperiments = useMemo(
    () => (data?.content ?? []) as GroupedExperiment[],
    [data?.content],
  );

  useEffect(() => {
    if (!data || isPlaceholderData || data.total > data.content.length) return;

    const deletedIds = new Set(
      missingIds.filter(
        (id) => !pinnedExperiments.some((experiment) => experiment.id === id),
      ),
    );
    if (!deletedIds.size) return;

    setPinnedIds((prev) => prev.filter((id) => !deletedIds.has(id)));
  }, [data, isPlaceholderData, missingIds, pinnedExperiments, setPinnedIds]);

  const experiments = useMemo(() => {
    if (!enabled) return rows;

    const loadedIds = new Set(rows.map((row) => row.id));
    const missing = pinnedExperiments.filter((row) => !loadedIds.has(row.id));

    return missing.length ? [...missing, ...rows] : rows;
  }, [enabled, rows, pinnedExperiments]);

  const rowPinning = useMemo<RowPinningState>(
    () => ({ top: pinnedIds, bottom: [] }),
    [pinnedIds],
  );

  const setRowPinning = useCallback<OnChangeFn<RowPinningState>>(
    (updater) => setPinnedIds(functionalUpdate(updater, rowPinning).top ?? []),
    [rowPinning, setPinnedIds],
  );

  const pinningConfig = useMemo<PinningConfig | undefined>(
    () => (enabled ? { rowPinning, setRowPinning } : undefined),
    [enabled, rowPinning, setRowPinning],
  );

  return { experiments, pinningConfig, refetch };
};

export default useExperimentsPinning;
