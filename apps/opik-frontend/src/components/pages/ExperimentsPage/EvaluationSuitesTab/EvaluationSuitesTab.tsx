import React, { useCallback, useMemo } from "react";
import { ColumnSort } from "@tanstack/react-table";
import { useNavigate } from "@tanstack/react-router";
import useLocalStorageState from "use-local-storage-state";
import { NumberParam, StringParam, useQueryParam } from "use-query-params";
import { keepPreviousData } from "@tanstack/react-query";
import { RotateCw } from "lucide-react";

import DataTable from "@/components/shared/DataTable/DataTable";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import Loader from "@/components/shared/Loader/Loader";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { Button } from "@/components/ui/button";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import PageBodyStickyTableWrapper from "@/components/layout/PageBodyStickyTableWrapper/PageBodyStickyTableWrapper";
import DataTableVirtualBody from "@/components/shared/DataTable/DataTableVirtualBody";
import useAppStore from "@/store/AppStore";
import useExperimentsList from "@/api/datasets/useExperimentsList";
import { DATASET_TYPE, Experiment } from "@/types/datasets";
import { convertColumnDataToColumn, mapColumnDataFields } from "@/lib/table";
import {
  EVALUATION_SUITES_PINNED_COLUMN,
  EVALUATION_SUITES_SELECTABLE_COLUMNS,
  EVALUATION_SUITES_DEFAULT_SELECTED_COLUMNS,
} from "./columns";
import useQueryParamAndLocalStorageState from "@/hooks/useQueryParamAndLocalStorageState";

const STORAGE_PREFIX = "evaluation-suites";
const PAGINATION_SIZE_KEY = `${STORAGE_PREFIX}-pagination-size`;
const COLUMNS_WIDTH_KEY = `${STORAGE_PREFIX}-columns-width`;
const SELECTED_COLUMNS_KEY = `${STORAGE_PREFIX}-selected-columns`;
const COLUMNS_ORDER_KEY = `${STORAGE_PREFIX}-columns-order`;
const COLUMNS_SORT_KEY = `${STORAGE_PREFIX}-columns-sort`;

const getRowId = (e: Experiment) => e.id;

const EvaluationSuitesTab: React.FC = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const navigate = useNavigate();

  const [search = "", setSearch] = useQueryParam("search", StringParam, {
    updateType: "replaceIn",
  });

  const [page = 1, setPage] = useQueryParam("page", NumberParam, {
    updateType: "replaceIn",
  });

  const [size, setSize] = useQueryParamAndLocalStorageState<
    number | null | undefined
  >({
    localStorageKey: PAGINATION_SIZE_KEY,
    queryKey: "size",
    defaultValue: 100,
    queryParamConfig: NumberParam,
    syncQueryWithLocalStorageOnInit: true,
  });

  const [columnsWidth, setColumnsWidth] = useLocalStorageState<
    Record<string, number>
  >(COLUMNS_WIDTH_KEY, {
    defaultValue: {},
  });

  const [selectedColumns, setSelectedColumns] = useLocalStorageState<string[]>(
    SELECTED_COLUMNS_KEY,
    {
      defaultValue: EVALUATION_SUITES_DEFAULT_SELECTED_COLUMNS,
    },
  );

  const [columnsOrder, setColumnsOrder] = useLocalStorageState<string[]>(
    COLUMNS_ORDER_KEY,
    {
      defaultValue: [],
    },
  );

  const [sortedColumns, setSortedColumns] = useLocalStorageState<ColumnSort[]>(
    COLUMNS_SORT_KEY,
    {
      defaultValue: [],
    },
  );

  const { data, isPending, isPlaceholderData, isFetching, refetch } =
    useExperimentsList(
      {
        workspaceName,
        search: search!,
        page: page!,
        size: size!,
        sorting: sortedColumns,
        datasetType: DATASET_TYPE.EVALUATION_SUITE,
      },
      {
        placeholderData: keepPreviousData,
        refetchInterval: 30000,
      },
    );

  const experiments = useMemo(() => data?.content ?? [], [data?.content]);
  const total = data?.total ?? 0;
  const noDataText = search
    ? "No search results"
    : "There are no evaluation suites yet";

  const columns = useMemo(() => {
    const sortableColumns = data?.sortable_by ?? [];

    return [
      mapColumnDataFields<Experiment, Experiment>(
        EVALUATION_SUITES_PINNED_COLUMN,
      ),
      ...convertColumnDataToColumn<Experiment, Experiment>(
        EVALUATION_SUITES_SELECTABLE_COLUMNS,
        {
          columnsOrder,
          selectedColumns,
          sortableColumns,
        },
      ),
    ];
  }, [columnsOrder, selectedColumns, data?.sortable_by]);

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      columnSizing: columnsWidth,
      onColumnResize: setColumnsWidth,
    }),
    [columnsWidth, setColumnsWidth],
  );

  const sortConfig = useMemo(
    () => ({
      enabled: true,
      sorting: sortedColumns,
      setSorting: setSortedColumns,
    }),
    [sortedColumns, setSortedColumns],
  );

  const handleRowClick = useCallback(
    (row: Experiment) => {
      navigate({
        to: "/$workspaceName/evaluation-suites/$suiteId/experiments/$experimentId",
        params: {
          suiteId: row.dataset_id,
          experimentId: row.id,
          workspaceName,
        },
      });
    },
    [navigate, workspaceName],
  );

  if (isPending) {
    return <Loader />;
  }

  return (
    <>
      <PageBodyStickyContainer
        className="flex flex-wrap items-center justify-between gap-x-8 gap-y-2 pb-6 pt-4"
        direction="bidirectional"
        limitWidth
      >
        <SearchInput
          searchText={search!}
          setSearchText={setSearch}
          placeholder="Search by name"
          className="w-[320px]"
          dimension="sm"
        />
        <div className="flex items-center gap-2">
          <TooltipWrapper content="Refresh experiments list">
            <Button
              variant="outline"
              size="icon-sm"
              className="shrink-0"
              onClick={() => refetch()}
            >
              <RotateCw />
            </Button>
          </TooltipWrapper>
          <ColumnsButton
            columns={EVALUATION_SUITES_SELECTABLE_COLUMNS}
            selectedColumns={selectedColumns}
            onSelectionChange={setSelectedColumns}
            order={columnsOrder}
            onOrderChange={setColumnsOrder}
          />
        </div>
      </PageBodyStickyContainer>
      <DataTable
        columns={columns}
        data={experiments}
        onRowClick={handleRowClick}
        sortConfig={sortConfig}
        resizeConfig={resizeConfig}
        getRowId={getRowId}
        noData={<DataTableNoData title={noDataText} />}
        TableWrapper={PageBodyStickyTableWrapper}
        TableBody={DataTableVirtualBody}
        stickyHeader
        showLoadingOverlay={isPlaceholderData && isFetching}
      />
      <PageBodyStickyContainer
        className="py-4"
        direction="horizontal"
        limitWidth
      >
        <DataTablePagination
          page={page!}
          pageChange={setPage}
          size={size!}
          sizeChange={setSize}
          total={total}
        />
      </PageBodyStickyContainer>
    </>
  );
};

export default EvaluationSuitesTab;
