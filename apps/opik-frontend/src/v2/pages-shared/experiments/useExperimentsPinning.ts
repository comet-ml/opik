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
import { GroupedExperiment } from "@/hooks/useGroupedExperimentsList";
import { PinningConfig } from "@/shared/DataTable/DataTable";

const MAX_PINNED_FETCH_SIZE = 100;

type UseExperimentsPinningParams = {
  rows: GroupedExperiment[];
  workspaceName: string;
  projectId?: string;
  pinnedIds: string[];
  setPinnedIds: Dispatch<SetStateAction<string[]>>;
  enabled: boolean;
};

const useExperimentsPinning = ({
  rows,
  workspaceName,
  projectId,
  pinnedIds,
  setPinnedIds,
  enabled,
}: UseExperimentsPinningParams) => {
  const { data, isPlaceholderData } = useExperimentsList(
    {
      workspaceName,
      projectId,
      experimentIds: pinnedIds,
      page: 1,
      size: MAX_PINNED_FETCH_SIZE,
    },
    {
      enabled: enabled && pinnedIds.length > 0,
      placeholderData: keepPreviousData,
    },
  );

  const pinnedExperiments = useMemo(
    () => (data?.content ?? []) as GroupedExperiment[],
    [data?.content],
  );

  useEffect(() => {
    if (!data || isPlaceholderData) return;

    const existingIds = new Set(pinnedExperiments.map((row) => row.id));
    setPinnedIds((prev) => {
      const next = prev.filter((id) => existingIds.has(id));
      return next.length === prev.length ? prev : next;
    });
  }, [data, isPlaceholderData, pinnedExperiments, setPinnedIds]);

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

  return { experiments, pinningConfig };
};

export default useExperimentsPinning;
