import { useState } from "react";
import {
  JsonParam,
  NumberParam,
  StringParam,
  useQueryParam,
} from "use-query-params";
import { ColumnSort, RowSelectionState } from "@tanstack/react-table";
import useLocalStorageState from "use-local-storage-state";

import { ROW_HEIGHT } from "@/types/shared";
import useQueryParamAndLocalStorageState from "@/hooks/useQueryParamAndLocalStorageState";

type UseExperimentItemsStateParams = {
  storagePrefix: string;
  defaultSelectedColumns?: string[];
};

const useExperimentItemsState = ({
  storagePrefix,
  defaultSelectedColumns = [],
}: UseExperimentItemsStateParams) => {
  const [page = 1, setPage] = useQueryParam("page", NumberParam, {
    updateType: "replaceIn",
  });

  const [size, setSize] = useQueryParamAndLocalStorageState<
    number | null | undefined
  >({
    localStorageKey: `${storagePrefix}-pagination-size`,
    queryKey: "size",
    defaultValue: 100,
    queryParamConfig: NumberParam,
    syncQueryWithLocalStorageOnInit: true,
  });

  const [height, setHeight] = useQueryParamAndLocalStorageState<
    string | null | undefined
  >({
    localStorageKey: `${storagePrefix}-row-height`,
    queryKey: "height",
    defaultValue: ROW_HEIGHT.small,
    queryParamConfig: StringParam,
    syncQueryWithLocalStorageOnInit: true,
  });

  const [search = "", setSearch] = useQueryParam("search", StringParam, {
    updateType: "replaceIn",
  });

  const [filters = [], setFilters] = useQueryParam("filters", JsonParam, {
    updateType: "replaceIn",
  });

  const [columnsWidth, setColumnsWidth] = useLocalStorageState<
    Record<string, number>
  >(`${storagePrefix}-columns-width`, {
    defaultValue: {},
  });

  const [selectedColumns, setSelectedColumns] = useLocalStorageState<string[]>(
    `${storagePrefix}-selected-columns`,
    {
      defaultValue: defaultSelectedColumns,
    },
  );

  const [columnsOrder, setColumnsOrder] = useLocalStorageState<string[]>(
    `${storagePrefix}-columns-order`,
    {
      defaultValue: [],
    },
  );

  const [scoresColumnsOrder, setScoresColumnsOrder] = useLocalStorageState<
    string[]
  >(`${storagePrefix}-scores-columns-order`, {
    defaultValue: [],
  });

  const [outputColumnsOrder, setOutputColumnsOrder] = useLocalStorageState<
    string[]
  >(`${storagePrefix}-output-columns-order`, {
    defaultValue: [],
  });

  const [sorting, setSorting] = useQueryParamAndLocalStorageState<ColumnSort[]>(
    {
      localStorageKey: `${storagePrefix}-sorting`,
      queryKey: "sorting",
      defaultValue: [],
      queryParamConfig: JsonParam,
    },
  );

  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});

  return {
    page,
    setPage,
    size,
    setSize,
    height,
    setHeight,
    search,
    setSearch,
    filters,
    setFilters,
    columnsWidth,
    setColumnsWidth,
    selectedColumns,
    setSelectedColumns,
    columnsOrder,
    setColumnsOrder,
    scoresColumnsOrder,
    setScoresColumnsOrder,
    outputColumnsOrder,
    setOutputColumnsOrder,
    sorting,
    setSorting,
    rowSelection,
    setRowSelection,
  };
};

export default useExperimentItemsState;
