import { useMemo } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { RowSelectionState } from "@tanstack/react-table";
import useOptimizationsList from "@/api/optimizations/useOptimizationsList";
import { Optimization } from "@/types/optimizations";
import {
  ACTIVE_OPTIMIZATION_FILTER,
  IN_PROGRESS_OPTIMIZATION_STATUSES,
} from "@/lib/optimizations";

const DEFAULT_PAGE_SIZE = 100;
const POLLING_INTERVAL_MS = 30000;

const hasInProgressRun = (optimizations: Optimization[]) =>
  optimizations.some((optimization) =>
    IN_PROGRESS_OPTIMIZATION_STATUSES.includes(optimization.status),
  );

type UseOptimizationsViewParams = {
  workspaceName: string;
  projectId?: string;
  datasetId?: string;
  search?: string;
  page: number;
  rowSelection: RowSelectionState;
  /** Poll only while a run is in progress (v2). Default false keeps v1's unconditional 30s. */
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
  // Workspace-wide probe: keepPreviousData holds only the current page, so a
  // RUNNING run on another page wouldn't otherwise keep the poll alive.
  const { data: activeData } = useOptimizationsList(
    {
      workspaceName,
      projectId,
      datasetId: datasetId || "",
      filters: ACTIVE_OPTIMIZATION_FILTER,
      page: 1,
      size: 1,
    },
    {
      enabled: pollWhileInProgress,
      refetchInterval: (query) =>
        (query.state.data?.total ?? 0) > 0 ? POLLING_INTERVAL_MS : false,
    },
  );
  const hasActiveRuns = (activeData?.total ?? 0) > 0;

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
        refetchInterval: (query) => {
          // v1 callers poll unconditionally; v2 callers poll only while a run
          // is active (detected workspace-wide or in progress on this page)
          // and stop once everything settles.
          if (!pollWhileInProgress) {
            return POLLING_INTERVAL_MS;
          }

          const shouldPoll =
            hasActiveRuns || hasInProgressRun(query.state.data?.content ?? []);

          return shouldPoll ? POLLING_INTERVAL_MS : false;
        },
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
