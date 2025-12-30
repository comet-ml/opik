import { useCallback, useMemo } from "react";
import { useNavigate } from "@tanstack/react-router";
import useAppStore from "@/store/AppStore";
import { generateExperimentIdFilter } from "@/lib/filters";
import { GroupedExperiment } from "@/hooks/useGroupedExperimentsList";
import { ColumnData } from "@/types/shared";

/**
 * Hook that provides navigation callback for trace_count column in experiments tables.
 * Navigates to project traces page filtered by experiment_id.
 */
export const useExperimentsTraceCountNavigation = () => {
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const navigateToExperimentTraces = useCallback(
    (row: GroupedExperiment) => {
      if (!row.project_id) return;

      navigate({
        to: "/$workspaceName/projects/$projectId/traces",
        params: {
          projectId: row.project_id,
          workspaceName,
        },
        search: {
          traces_filters: generateExperimentIdFilter(row.id),
        },
      });
    },
    [navigate, workspaceName],
  );

  return navigateToExperimentTraces;
};

/**
 * Utility function that enhances columns array with trace_count navigation callback.
 * Adds the callback to the trace_count column's customMeta.
 */
export const enhanceColumnsWithTraceCountCallback = <
  T extends GroupedExperiment,
>(
  columns: ColumnData<T>[],
  callback: (row: T) => void,
): ColumnData<T>[] => {
  return columns.map((column) =>
    column.id === "trace_count"
      ? {
          ...column,
          customMeta: {
            ...column.customMeta,
            callback,
          },
        }
      : column,
  );
};

/**
 * Hook that returns columns enhanced with trace_count navigation callback.
 * Combines useExperimentsTraceCountNavigation with enhanceColumnsWithTraceCountCallback.
 */
export const useExperimentsColumnsWithTraceCount = <
  T extends GroupedExperiment,
>(
  columns: ColumnData<T>[],
): ColumnData<T>[] => {
  const navigateToExperimentTraces = useExperimentsTraceCountNavigation();

  return useMemo(
    () =>
      enhanceColumnsWithTraceCountCallback(columns, navigateToExperimentTraces),
    [columns, navigateToExperimentTraces],
  );
};
