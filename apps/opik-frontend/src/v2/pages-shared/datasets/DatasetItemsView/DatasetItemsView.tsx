import React, { useCallback, useMemo } from "react";
import {
  JsonParam,
  NumberParam,
  StringParam,
  useQueryParam,
} from "use-query-params";
import useLocalStorageState from "use-local-storage-state";
import { keepPreviousData } from "@tanstack/react-query";

import DataTable from "@/shared/DataTable/DataTable";
import DataTablePagination from "@/shared/DataTablePagination/DataTablePagination";
import DataTableRowHeightSelector from "@/shared/DataTableRowHeightSelector/DataTableRowHeightSelector";
import DataTableEmptyContent from "@/shared/DataTableNoData/DataTableEmptyContent";
import ColumnsButton from "@/shared/ColumnsButton/ColumnsButton";
import SearchInput from "@/shared/SearchInput/SearchInput";
import FiltersButton from "@/shared/FiltersButton/FiltersButton";
import { useDynamicColumnsCache } from "@/hooks/useDynamicColumnsCache";
import useQueryParamAndLocalStorageState from "@/hooks/useQueryParamAndLocalStorageState";
import useDatasetItemsList from "@/api/datasets/useDatasetItemsList";
import { DatasetItem } from "@/types/datasets";
import { Filters } from "@/types/filters";
import {
  COLUMN_DATA_ID,
  ColumnData,
  DynamicColumn,
  ROW_HEIGHT,
} from "@/types/shared";
import { convertColumnDataToColumn } from "@/lib/table";
import {
  buildDatasetFilterColumns,
  mapDynamicColumnTypesToColumnType,
} from "@/lib/filters";
import { transformDataColumnFilters } from "@/lib/dataset-items";
import { EditPanelRenderProps } from "@/v2/pages-shared/datasets/DatasetItemsTab/DatasetItemsTab";
import {
  buildDatasetColumns,
  buildSuiteColumns,
  DATASET_DEFAULT_SELECTED_COLUMNS,
  SUITE_DEFAULT_SELECTED_COLUMNS,
} from "@/v2/pages-shared/datasets/DatasetItemsPage/datasetItemsPageConfig";
import {
  DATASET_VIEW_STORAGE_KEYS,
  DVS_QUERY_PREFIX,
  SUITE_VIEW_STORAGE_KEYS,
} from "./constants";
import {
  SuiteItemAssertionsCell,
  SuiteItemExecutionPolicyCell,
} from "./SuiteItemCells";

const SUITE_ROW_DATA_CELLS: Record<string, unknown> = {
  assertions: SuiteItemAssertionsCell,
  execution_policy: SuiteItemExecutionPolicyCell,
};

const getRowId = (d: DatasetItem) => d.id;

type DatasetItemsViewProps = {
  datasetId: string;
  versionId?: string;
  isTestSuite: boolean;
  renderItemPanel: (props: EditPanelRenderProps) => React.ReactNode;
};

const DatasetItemsView: React.FC<DatasetItemsViewProps> = ({
  datasetId,
  versionId,
  isTestSuite,
  renderItemPanel,
}) => {
  const storageKeys = isTestSuite
    ? SUITE_VIEW_STORAGE_KEYS
    : DATASET_VIEW_STORAGE_KEYS;
  const itemName = isTestSuite ? "test case" : "record";

  const [activeRowId = "", setActiveRowId] = useQueryParam(
    `${DVS_QUERY_PREFIX}row`,
    StringParam,
    { updateType: "replaceIn" },
  );
  const [page = 1, setPage] = useQueryParam(
    `${DVS_QUERY_PREFIX}page`,
    NumberParam,
    { updateType: "replaceIn" },
  );
  const [search = "", setSearch] = useQueryParam(
    `${DVS_QUERY_PREFIX}search`,
    StringParam,
    { updateType: "replaceIn" },
  );
  const [filters = [], setFilters] = useQueryParam<Filters, Filters>(
    `${DVS_QUERY_PREFIX}filters`,
    JsonParam,
    { updateType: "replaceIn" },
  );
  const [size, setSize] = useQueryParamAndLocalStorageState<
    number | null | undefined
  >({
    localStorageKey: storageKeys.paginationSizeKey,
    queryKey: `${DVS_QUERY_PREFIX}size`,
    defaultValue: 10,
    queryParamConfig: NumberParam,
    syncQueryWithLocalStorageOnInit: true,
  });
  const [height, setHeight] = useQueryParamAndLocalStorageState<
    string | null | undefined
  >({
    localStorageKey: storageKeys.rowHeightKey,
    queryKey: `${DVS_QUERY_PREFIX}height`,
    defaultValue: ROW_HEIGHT.small,
    queryParamConfig: StringParam,
    syncQueryWithLocalStorageOnInit: true,
  });

  const transformedFilters = useMemo(
    () => (filters ? transformDataColumnFilters(filters) : filters),
    [filters],
  );

  const { data, isPending, isPlaceholderData, isFetching, isError } =
    useDatasetItemsList(
      {
        datasetId,
        versionId,
        filters: transformedFilters,
        page: page as number,
        size: size as number,
        search: search ?? "",
        truncate: false,
      },
      { placeholderData: keepPreviousData },
    );

  const totalCount = data?.total ?? 0;
  const rows = useMemo(() => data?.content ?? [], [data?.content]);
  const datasetColumns = useMemo(
    () =>
      (data?.columns ?? []).sort((c1, c2) => c1.name.localeCompare(c2.name)),
    [data?.columns],
  );

  const dynamicDatasetColumns = useMemo(
    () =>
      datasetColumns.map<DynamicColumn>((c) => ({
        id: `${COLUMN_DATA_ID}.${c.name}`,
        label: c.name,
        columnType: mapDynamicColumnTypesToColumnType(c.types),
      })),
    [datasetColumns],
  );

  const dynamicColumnsIds = useMemo(
    () => dynamicDatasetColumns.map((c) => c.id),
    [dynamicDatasetColumns],
  );

  const [selectedColumns, setSelectedColumns] = useLocalStorageState<string[]>(
    storageKeys.selectedColumnsKey,
    {
      defaultValue: isTestSuite
        ? SUITE_DEFAULT_SELECTED_COLUMNS
        : DATASET_DEFAULT_SELECTED_COLUMNS,
    },
  );

  useDynamicColumnsCache({
    dynamicColumnsKey: storageKeys.dynamicColumnsKey,
    dynamicColumnsIds,
    setSelectedColumns,
  });

  const columnsData = useMemo<ColumnData<DatasetItem>[]>(
    () =>
      isTestSuite
        ? buildSuiteColumns().map((column) =>
            SUITE_ROW_DATA_CELLS[column.id]
              ? { ...column, cell: SUITE_ROW_DATA_CELLS[column.id] as never }
              : column,
          )
        : buildDatasetColumns(datasetColumns, dynamicDatasetColumns),
    [isTestSuite, datasetColumns, dynamicDatasetColumns],
  );

  const [columnsOrder, setColumnsOrder] = useLocalStorageState<string[]>(
    storageKeys.columnsOrderKey,
    { defaultValue: [] },
  );
  const [columnsWidth, setColumnsWidth] = useLocalStorageState<
    Record<string, number>
  >(storageKeys.columnsWidthKey, { defaultValue: {} });

  const columns = useMemo(
    () =>
      convertColumnDataToColumn<DatasetItem, DatasetItem>(columnsData, {
        columnsOrder,
        selectedColumns,
      }),
    [columnsData, columnsOrder, selectedColumns],
  );

  const filtersColumnData = useMemo(
    () => buildDatasetFilterColumns(datasetColumns, true),
    [datasetColumns],
  );

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      columnSizing: columnsWidth,
      onColumnResize: setColumnsWidth,
    }),
    [columnsWidth, setColumnsWidth],
  );

  const handleSearchChange = useCallback(
    (newSearch: string | null) => {
      setSearch(newSearch);
      if (page !== 1) {
        setPage(1);
      }
    },
    [setSearch, setPage, page],
  );

  const handleRowClick = useCallback(
    (row: DatasetItem) =>
      setActiveRowId((state) => (row.id === state ? "" : row.id)),
    [setActiveRowId],
  );

  const handleClose = useCallback(() => setActiveRowId(""), [setActiveRowId]);

  const isTableLoading = isPending || (isPlaceholderData && rows.length === 0);

  return (
    <>
      <div className="mb-4 flex items-center justify-between gap-4">
        <div className="flex min-w-0 flex-1 items-center gap-2">
          <SearchInput
            searchText={search ?? ""}
            setSearchText={handleSearchChange}
            placeholder="Search"
            className="w-full max-w-[320px]"
            dimension="sm"
          />
          <FiltersButton
            columns={filtersColumnData}
            filters={filters}
            onChange={setFilters}
            layout="icon"
          />
        </div>
        <div className="flex shrink-0 items-center gap-2">
          <DataTableRowHeightSelector
            type={height as ROW_HEIGHT}
            setType={setHeight}
          />
          <ColumnsButton
            columns={columnsData}
            selectedColumns={selectedColumns}
            onSelectionChange={setSelectedColumns}
            order={columnsOrder}
            onOrderChange={setColumnsOrder}
          />
        </div>
      </div>
      <DataTable
        columns={columns}
        data={rows}
        onRowClick={handleRowClick}
        activeRowId={activeRowId ?? ""}
        resizeConfig={resizeConfig}
        showSkeleton={isTableLoading}
        showLoadingOverlay={!isTableLoading && isPlaceholderData && isFetching}
        getRowId={getRowId}
        rowHeight={height as ROW_HEIGHT}
        noData={
          <DataTableEmptyContent
            title={
              isError
                ? "Couldn't load this version"
                : search || filters.length
                  ? `No ${itemName}s match your search or filters`
                  : `No ${itemName}s in this version`
            }
            description=""
          />
        }
      />
      <div className="flex justify-end py-4">
        <DataTablePagination
          page={page as number}
          pageChange={setPage}
          size={size as number}
          sizeChange={setSize}
          total={totalCount}
        />
      </div>
      {renderItemPanel({
        datasetItemId: activeRowId as string,
        datasetId,
        columns: datasetColumns,
        onClose: handleClose,
        isOpen: Boolean(activeRowId),
        rows,
        setActiveRowId,
      })}
    </>
  );
};

export default DatasetItemsView;
