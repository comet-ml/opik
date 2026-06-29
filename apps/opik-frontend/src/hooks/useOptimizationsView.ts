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
  // Workspace-wide "is anything running" probe so client-side pagination can't
  // silently stop the auto-refresh when a RUNNING run sits on another page
  // (with keepPreviousData the list query only ever holds the current page).
  // Only runs for pollWhileInProgress (v2) callers and polls itself only while a
  // run is active, so everything still settles to no polling once runs finish.
  // Scope: detects RUNNING workspace-wide; a cross-page INITIALIZED run (brief,
  // and caught by the per-page check once it surfaces) and a run started in
  // another tab while this one stays focused are not auto-detected until the
  // next window-focus refetch — acceptable edge cases for this list.
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
        refetchInterval: pollWhileInProgress
          ? (query) =>
              hasActiveRuns ||
              (query.state.data?.content ?? []).some((optimization) =>
                IN_PROGRESS_OPTIMIZATION_STATUSES.includes(optimization.status),
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
