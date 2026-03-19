import { useCallback, useMemo } from "react";
import { useNavigate } from "@tanstack/react-router";
import { ColumnSort } from "@tanstack/react-table";
import useLocalStorageState from "use-local-storage-state";
import { StringParam, useQueryParam } from "use-query-params";

import { COLUMN_ID_ID, COLUMN_NAME_ID, ROW_HEIGHT } from "@/types/shared";
import { AggregatedCandidate } from "@/types/optimizations";
import { sortCandidates } from "@/lib/optimizations";

const SELECTED_COLUMNS_KEY = "optimization-experiments-selected-columns-v3";
const COLUMNS_WIDTH_KEY = "optimization-experiments-columns-width";
const COLUMNS_ORDER_KEY = "optimization-experiments-columns-order";
const COLUMNS_SORT_KEY = "optimization-experiments-columns-sort-v2";
const ROW_HEIGHT_KEY = "optimization-experiments-row-height";

const DEFAULT_SELECTED_COLUMNS: string[] = [
  COLUMN_NAME_ID,
  "step",
  "objective_name",
  "runtime_cost",
  "latency",
  "trace_count",
  "trial_status",
  "created_at",
];

const DEFAULT_COLUMNS_ORDER: string[] = [
  COLUMN_NAME_ID,
  "step",
  COLUMN_ID_ID,
  "objective_name",
  "runtime_cost",
  "latency",
  "trace_count",
  "trial_status",
  "created_at",
];

const DEFAULT_SORTING: ColumnSort[] = [{ id: COLUMN_NAME_ID, desc: false }];

interface UseOptimizationTableStateParams {
  candidates: AggregatedCandidate[];
  workspaceName: string;
  optimizationId: string;
}

export const useOptimizationTableState = ({
  candidates,
  workspaceName,
  optimizationId,
}: UseOptimizationTableStateParams) => {
  const navigate = useNavigate();

  const [search = "", setSearch] = useQueryParam("search", StringParam, {
    updateType: "replaceIn",
  });

  const [sortedColumns, setSortedColumns] = useLocalStorageState<ColumnSort[]>(
    COLUMNS_SORT_KEY,
    {
      defaultValue: DEFAULT_SORTING,
    },
  );

  const [selectedColumns, setSelectedColumns] = useLocalStorageState<string[]>(
    SELECTED_COLUMNS_KEY,
    {
      defaultValue: DEFAULT_SELECTED_COLUMNS,
    },
  );

  const [columnsOrder, setColumnsOrder] = useLocalStorageState<string[]>(
    COLUMNS_ORDER_KEY,
    {
      defaultValue: DEFAULT_COLUMNS_ORDER,
    },
  );

  const [columnsWidth, setColumnsWidth] = useLocalStorageState<
    Record<string, number>
  >(COLUMNS_WIDTH_KEY, {
    defaultValue: {},
  });

  const [height, setHeight] = useLocalStorageState<ROW_HEIGHT>(ROW_HEIGHT_KEY, {
    defaultValue: ROW_HEIGHT.small,
  });

  const noData = !search;
  const noDataText = noData ? "There are no trials yet" : "No search results";

  const rows = useMemo(() => {
    const filtered = candidates.filter(({ name }) =>
      name.toLowerCase().includes((search ?? "").toLowerCase()),
    );
    return sortCandidates(filtered, sortedColumns);
  }, [candidates, search, sortedColumns]);

  const handleRowClick = useCallback(
    (row: AggregatedCandidate) => {
      navigate({
        to: "/$workspaceName/optimizations/$optimizationId/trials",
        params: {
          optimizationId,
          workspaceName,
        },
        search: {
          trials: row.experimentIds,
          trialNumber: row.trialNumber,
        },
      });
    },
    [navigate, workspaceName, optimizationId],
  );

  return {
    search,
    setSearch,
    noDataText,
    rows,
    sortedColumns,
    setSortedColumns,
    selectedColumns,
    setSelectedColumns,
    columnsOrder,
    setColumnsOrder,
    columnsWidth,
    setColumnsWidth,
    height,
    setHeight,
    handleRowClick,
  };
};
