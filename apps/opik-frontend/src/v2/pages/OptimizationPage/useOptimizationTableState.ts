import { useCallback, useMemo } from "react";
import { ColumnSort } from "@tanstack/react-table";
import useLocalStorageState from "use-local-storage-state";
import { NumberParam, StringParam, useQueryParam } from "use-query-params";

import { COLUMN_ID_ID, COLUMN_NAME_ID, ROW_HEIGHT } from "@/types/shared";
import { AggregatedCandidate } from "@/types/optimizations";
import { sortCandidates } from "@/lib/optimizations";

const SELECTED_COLUMNS_KEY = "optimization-experiments-selected-columns-v4";
const COLUMNS_WIDTH_KEY = "optimization-experiments-columns-width";
const COLUMNS_ORDER_KEY = "optimization-experiments-columns-order";
const COLUMNS_SORT_KEY = "optimization-experiments-columns-sort-v2";
const ROW_HEIGHT_KEY = "optimization-experiments-row-height";
const PAGE_SIZE_KEY = "optimization-experiments-page-size";

const DEFAULT_PAGE_SIZE = 50;

// "Trial items" (trace_count) stays available in the columns picker but is out
// of the default view.
const DEFAULT_SELECTED_COLUMNS: string[] = [
  COLUMN_NAME_ID,
  "step",
  "prompt",
  "objective_name",
  "runtime_cost",
  "latency",
  "trial_status",
  "created_at",
];

const DEFAULT_COLUMNS_ORDER: string[] = [
  COLUMN_NAME_ID,
  "step",
  "prompt",
  COLUMN_ID_ID,
  "objective_name",
  "runtime_cost",
  "latency",
  "trial_status",
  "created_at",
];

const DEFAULT_SORTING: ColumnSort[] = [{ id: COLUMN_NAME_ID, desc: false }];

interface UseOptimizationTableStateParams {
  candidates: AggregatedCandidate[];
  /** Opens the trial sidebar for the clicked row. */
  onRowClick: (row: AggregatedCandidate) => void;
}

export const useOptimizationTableState = ({
  candidates,
  onRowClick,
}: UseOptimizationTableStateParams) => {
  const [search = "", setSearchParam] = useQueryParam("search", StringParam, {
    updateType: "replaceIn",
  });

  const [page = 1, setPage] = useQueryParam("page", NumberParam, {
    updateType: "replaceIn",
  });

  const [pageSize, setStoredPageSize] = useLocalStorageState<number>(
    PAGE_SIZE_KEY,
    {
      defaultValue: DEFAULT_PAGE_SIZE,
    },
  );

  const setSearch = useCallback(
    (value: string) => {
      setSearchParam(value);
      setPage(1);
    },
    [setSearchParam, setPage],
  );

  const setPageSize = useCallback(
    (size: number) => {
      setStoredPageSize(size);
      setPage(1);
    },
    [setStoredPageSize, setPage],
  );

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

  const filteredRows = useMemo(() => {
    const filtered = candidates.filter(({ name }) =>
      name.toLowerCase().includes((search ?? "").toLowerCase()),
    );
    return sortCandidates(filtered, sortedColumns);
  }, [candidates, search, sortedColumns]);

  const total = filteredRows.length;

  // Client-side pagination: all trials are already in memory, so paging is a
  // slice over the filtered + sorted list.
  const rows = useMemo(() => {
    const safePage = Math.max(page ?? 1, 1);
    return filteredRows.slice((safePage - 1) * pageSize, safePage * pageSize);
  }, [filteredRows, page, pageSize]);

  return {
    search,
    setSearch,
    noDataText,
    rows,
    total,
    page: page ?? 1,
    setPage,
    pageSize,
    setPageSize,
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
    handleRowClick: onRowClick,
  };
};
