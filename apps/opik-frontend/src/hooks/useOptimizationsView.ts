import { useMemo } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { RowSelectionState } from "@tanstack/react-table";
import useOptimizationsList from "@/api/optimizations/useOptimizationsList";
import { Optimization, OPTIMIZATION_STATUS } from "@/types/optimizations";

const DEFAULT_PAGE_SIZE = 100;
const POLLING_INTERVAL_MS = 30000;

const IN_PROGRESS_STATUSES = [
  OPTIMIZATION_STATUS.RUNNING,
  OPTIMIZATION_STATUS.INITIALIZED,
];

type UseOptimizationsViewParams = {
  workspaceName: string;
  projectId?: string;
  datasetId?: string;
  search?: string;
  page: number;
  rowSelection: RowSelectionState;
  /**
   * When true, poll only while a run in the list is in progress (and stop once
   * everything is settled). Defaults to false, which keeps the original
   * unconditional 30s polling for existing (v1) callers.
   */
  pollWhileInProgress?: boolean;
};

export const useOptimizationsView = ({
  workspaceName,
  projectId,
  datasetId,
  search,
  page,
  rowSelection,
  pollWhileInProgress = false,
}: UseOptimizationsViewParams) => {
  const { data, isPending, isPlaceholderData, isFetching, refetch } =
    useOptimizationsList(
      {
        workspaceName,
        projectId,
        datasetId: datasetId || "",
        search: search || "",
        page,
        size: DEFAULT_PAGE_SIZE,
      },
      {
        placeholderData: keepPreviousData,
        refetchInterval: pollWhileInProgress
          ? (query) =>
              (query.state.data?.content ?? []).some((optimization) =>
                IN_PROGRESS_STATUSES.includes(optimization.status),
              )
                ? POLLING_INTERVAL_MS
                : false
          : POLLING_INTERVAL_MS,
      },
    );

  const optimizations = useMemo(() => data?.content ?? [], [data?.content]);
  const total = data?.total ?? 0;

  const selectedRows: Array<Optimization> = useMemo(() => {
    return optimizations.filter((row) => rowSelection[row.id]);
  }, [rowSelection, optimizations]);

  return {
    optimizations,
    total,
    selectedRows,
    isPending,
    isPlaceholderData,
    isFetching,
    pageSize: DEFAULT_PAGE_SIZE,
    refetch,
  };
};
