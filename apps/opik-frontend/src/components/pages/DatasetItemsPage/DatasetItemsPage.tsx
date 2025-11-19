import React, { useCallback, useMemo, useRef, useState } from "react";
import { ColumnPinningState, RowSelectionState } from "@tanstack/react-table";
import get from "lodash/get";
import {
  JsonParam,
  NumberParam,
  StringParam,
  useQueryParam,
} from "use-query-params";
import useLocalStorageState from "use-local-storage-state";
import { keepPreviousData } from "@tanstack/react-query";

import Loader from "@/components/shared/Loader/Loader";
import DataTable from "@/components/shared/DataTable/DataTable";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import { useDatasetIdFromURL } from "@/hooks/useDatasetIdFromURL";
import { useDynamicColumnsCache } from "@/hooks/useDynamicColumnsCache";
import useQueryParamAndLocalStorageState from "@/hooks/useQueryParamAndLocalStorageState";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import DateTag from "@/components/shared/DateTag/DateTag";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import FiltersButton from "@/components/shared/FiltersButton/FiltersButton";
import useDatasetItemsList from "@/api/datasets/useDatasetItemsList";
import useDatasetById from "@/api/datasets/useDatasetById";
import { DatasetItem } from "@/types/datasets";
import { Filters } from "@/types/filters";
import {
  COLUMN_ID_ID,
  COLUMN_SELECT_ID,
  COLUMN_TYPE,
  ColumnData,
  DynamicColumn,
  ROW_HEIGHT,
} from "@/types/shared";
import DatasetItemEditor from "@/components/pages/DatasetItemsPage/DatasetItemEditor/DatasetItemEditor";
import DatasetItemsActionsPanel from "@/components/pages/DatasetItemsPage/DatasetItemsActionsPanel";
import { DatasetItemRowActionsCell } from "@/components/pages/DatasetItemsPage/DatasetItemRowActionsCell";
import DataTableRowHeightSelector from "@/components/shared/DataTableRowHeightSelector/DataTableRowHeightSelector";
import AddEditDatasetItemDialog from "@/components/pages/DatasetItemsPage/AddEditDatasetItemDialog";
import AddDatasetItemSidebar from "@/components/pages/DatasetItemsPage/AddDatasetItemSidebar";
import DatasetTagsList from "@/components/pages/DatasetItemsPage/DatasetTagsList";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import { convertColumnDataToColumn, mapColumnDataFields } from "@/lib/table";
import { buildDocsUrl } from "@/lib/utils";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import AutodetectCell from "@/components/shared/DataTableCells/AutodetectCell";
import LinkCell from "@/components/shared/DataTableCells/LinkCell";
import ListCell from "@/components/shared/DataTableCells/ListCell";
import { formatDate } from "@/lib/date";
import { mapDynamicColumnTypesToColumnType } from "@/lib/filters";
import {
  generateActionsColumDef,
  generateSelectColumDef,
} from "@/components/shared/DataTable/utils";
import { useTruncationEnabled } from "@/components/server-sync-provider";
import UseDatasetDropdown from "@/components/pages/DatasetItemsPage/UseDatasetDropdown";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";
import { DATASET_ITEM_DATA_PREFIX } from "@/constants/datasets";

const getRowId = (d: DatasetItem) => d.id;

export const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID, COLUMN_ID_ID],
  right: [],
};

export const DEFAULT_SELECTED_COLUMNS: string[] = ["id", "created_at", "tags"];

const SELECTED_COLUMNS_KEY = "dataset-items-selected-columns";
const COLUMNS_WIDTH_KEY = "dataset-items-columns-width";
const COLUMNS_ORDER_KEY = "dataset-items-columns-order";
const DYNAMIC_COLUMNS_KEY = "dataset-items-dynamic-columns";
const PAGINATION_SIZE_KEY = "dataset-items-pagination-size";
const ROW_HEIGHT_KEY = "dataset-items-row-height";

const DatasetItemsPage = () => {
  const datasetId = useDatasetIdFromURL();
  const truncationEnabled = useTruncationEnabled();

  const [activeRowId = "", setActiveRowId] = useQueryParam("row", StringParam, {
    updateType: "replaceIn",
  });

  const [page = 1, setPage] = useQueryParam("page", NumberParam, {
    updateType: "replaceIn",
  });

  const [search = "", setSearch] = useQueryParam("search", StringParam, {
    updateType: "replaceIn",
  });

  const [filters = [], setFilters] = useQueryParam<Filters, Filters>(
    "filters",
    JsonParam,
    {
      updateType: "replaceIn",
    },
  );

  const [size, setSize] = useQueryParamAndLocalStorageState<
    number | null | undefined
  >({
    localStorageKey: PAGINATION_SIZE_KEY,
    queryKey: "size",
    defaultValue: 10,
    queryParamConfig: NumberParam,
    syncQueryWithLocalStorageOnInit: true,
  });

  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});

  const [height, setHeight] = useQueryParamAndLocalStorageState<
    string | null | undefined
  >({
    localStorageKey: ROW_HEIGHT_KEY,
    queryKey: "height",
    defaultValue: ROW_HEIGHT.small,
    queryParamConfig: StringParam,
    syncQueryWithLocalStorageOnInit: true,
  });

  const resetDialogKeyRef = useRef(0);
  const [openDialog, setOpenDialog] = useState<boolean>(false);
  const [openAddSidebar, setOpenAddSidebar] = useState<boolean>(false);

  const { data: dataset } = useDatasetById({
    datasetId,
  });

  const { data, isPending } = useDatasetItemsList(
    {
      datasetId,
      filters,
      page: page as number,
      size: size as number,
      search: search!,
      truncate: truncationEnabled,
    },
    {
      placeholderData: keepPreviousData,
    },
  );

  const { refetch: refetchExportData } = useDatasetItemsList(
    {
      datasetId,
      filters,
      page: page as number,
      size: size as number,
      search: search!,
      truncate: false,
    },
    {
      enabled: false,
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
      defaultValue: [],
    },
  );

  const [columnsWidth, setColumnsWidth] = useLocalStorageState<
    Record<string, number>
  >(COLUMNS_WIDTH_KEY, {
    defaultValue: {},
  });

  const rows: Array<DatasetItem> = useMemo(() => data?.content ?? [], [data]);
  const noDataText = "There are no dataset items yet";

  const handleSearchChange = useCallback(
    (newSearch: string | null) => {
      setSearch(newSearch);
      if (page !== 1) {
        setPage(1);
      }
    },
    [setSearch, setPage, page],
  );

  const selectedRows: DatasetItem[] = useMemo(() => {
    return rows.filter((row) => rowSelection[row.id]);
  }, [rowSelection, rows]);

  const getDataForExport = useCallback(async (): Promise<DatasetItem[]> => {
    const result = await refetchExportData();

    if (result.error) {
      throw result.error;
    }

    if (!result.data?.content) {
      throw new Error("Failed to fetch data");
    }

    const allRows = result.data.content;
    const selectedIds = Object.keys(rowSelection);

    return allRows.filter((row) => selectedIds.includes(row.id));
  }, [refetchExportData, rowSelection]);

  const dynamicDatasetColumns = useMemo(() => {
    return (data?.columns ?? [])
      .sort((c1, c2) => c1.name.localeCompare(c2.name))
      .map<DynamicColumn>((c) => ({
        id: `${DATASET_ITEM_DATA_PREFIX}.${c.name}`,
        label: c.name,
        columnType: mapDynamicColumnTypesToColumnType(c.types),
      }));
  }, [data]);

  const dynamicColumnsIds = useMemo(
    () => dynamicDatasetColumns.map((c) => c.id),
    [dynamicDatasetColumns],
  );

  useDynamicColumnsCache({
    dynamicColumnsKey: DYNAMIC_COLUMNS_KEY,
    dynamicColumnsIds,
    setSelectedColumns,
  });

  const columnsData = useMemo(() => {
    const retVal: ColumnData<DatasetItem>[] = dynamicDatasetColumns.map(
      ({ label, id, columnType }) =>
        ({
          id,
          label,
          type: columnType,
          accessorFn: (row) => get(row, ["data", label], ""),
          cell: AutodetectCell as never,
        }) as ColumnData<DatasetItem>,
    );

    retVal.push({
      id: "tags",
      label: "Tags",
      type: COLUMN_TYPE.list,
      accessorFn: (row) => row.tags || [],
      cell: ListCell as never,
    });

    retVal.push({
      id: "created_at",
      label: "Created",
      type: COLUMN_TYPE.time,
      accessorFn: (row) => formatDate(row.created_at),
    });

    retVal.push({
      id: "last_updated_at",
      label: "Last updated",
      type: COLUMN_TYPE.time,
      accessorFn: (row) => formatDate(row.last_updated_at),
    });

    retVal.push({
      id: "created_by",
      label: "Created by",
      type: COLUMN_TYPE.string,
    });

    return retVal;
  }, [dynamicDatasetColumns]);

  const filtersColumnData = useMemo(() => {
    return [
      {
        id: "tags",
        label: "Tags",
        type: COLUMN_TYPE.list,
      },
    ];
  }, []);

  const handleRowClick = useCallback(
    (row: DatasetItem) => {
      setActiveRowId((state) => (row.id === state ? "" : row.id));
    },
    [setActiveRowId],
  );

  const columns = useMemo(() => {
    return [
      generateSelectColumDef<DatasetItem>(),
      mapColumnDataFields<DatasetItem, DatasetItem>({
        id: COLUMN_ID_ID,
        label: "ID",
        type: COLUMN_TYPE.string,
        cell: LinkCell as never,
        customMeta: {
          callback: handleRowClick,
          asId: true,
        },
      }),
      ...convertColumnDataToColumn<DatasetItem, DatasetItem>(columnsData, {
        columnsOrder,
        selectedColumns,
      }),
      generateActionsColumDef({
        cell: DatasetItemRowActionsCell,
      }),
    ];
  }, [columnsData, columnsOrder, handleRowClick, selectedColumns]);

  const columnsToExport = useMemo(() => {
    return columns
      .map((c) => get(c, "accessorKey", ""))
      .filter((c) =>
        c === COLUMN_SELECT_ID
          ? false
          : selectedColumns.includes(c) ||
            (DEFAULT_COLUMN_PINNING.left || []).includes(c),
      );
  }, [columns, selectedColumns]);

  const handleNewDatasetItemClick = useCallback(() => {
    if (data?.total && data.total > 0) {
      setOpenAddSidebar(true);
    } else {
      setOpenDialog(true);
      resetDialogKeyRef.current = resetDialogKeyRef.current + 1;
    }
  }, [data?.total]);

  const handleClose = useCallback(() => setActiveRowId(""), [setActiveRowId]);

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      columnSizing: columnsWidth,
      onColumnResize: setColumnsWidth,
    }),
    [columnsWidth, setColumnsWidth],
  );

  if (isPending) {
    return <Loader />;
  }

  return (
    <div className="pt-6">
      <div className="mb-4">
        <div className="mb-4 flex items-center justify-between gap-2">
          <h1 className="comet-title-l truncate break-words">
            {dataset?.name}
          </h1>
          <div className="flex items-center gap-2">
            <UseDatasetDropdown
              datasetName={dataset?.name}
              datasetId={datasetId}
            />
          </div>
        </div>
        {dataset?.description && (
          <div className="-mt-3 mb-4 text-muted-slate">
            {dataset.description}
          </div>
        )}
        {dataset?.created_at && (
          <div className="mb-2 flex gap-2 overflow-x-auto">
            <DateTag
              date={dataset?.created_at}
              resource={RESOURCE_TYPE.dataset}
            />
          </div>
        )}
        <DatasetTagsList
          tags={dataset?.tags ?? []}
          dataset={dataset}
          datasetId={datasetId}
        />
      </div>
      <div className="mb-4 flex items-center justify-between gap-8">
        <div className="flex items-center gap-2">
          <SearchInput
            searchText={search!}
            setSearchText={handleSearchChange}
            placeholder="Search"
            className="w-[320px]"
            dimension="sm"
          />
          <FiltersButton
            columns={filtersColumnData}
            filters={filters}
            onChange={setFilters}
          />
        </div>
        <div className="flex items-center gap-2">
          <DatasetItemsActionsPanel
            getDataForExport={getDataForExport}
            selectedDatasetItems={selectedRows}
            datasetId={datasetId}
            datasetName={dataset?.name ?? ""}
            columnsToExport={columnsToExport}
            dynamicColumns={dynamicColumnsIds}
          />
          <Separator orientation="vertical" className="mx-2 h-4" />
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
          ></ColumnsButton>
          <Button
            variant="default"
            size="sm"
            onClick={handleNewDatasetItemClick}
          >
            Create dataset item
          </Button>
        </div>
      </div>
      <DataTable
        columns={columns}
        data={rows}
        onRowClick={handleRowClick}
        activeRowId={activeRowId ?? ""}
        resizeConfig={resizeConfig}
        selectionConfig={{
          rowSelection,
          setRowSelection,
        }}
        getRowId={getRowId}
        rowHeight={height as ROW_HEIGHT}
        columnPinning={DEFAULT_COLUMN_PINNING}
        noData={
          <DataTableNoData title={noDataText}>
            <Button variant="link">
              <a
                href={buildDocsUrl(
                  "/evaluation/manage_datasets",
                  "#insert-items",
                )}
                target="_blank"
                rel="noreferrer"
              >
                Check our documentation
              </a>
            </Button>
          </DataTableNoData>
        }
      />
      <div className="py-4">
        <DataTablePagination
          page={page as number}
          pageChange={setPage}
          size={size as number}
          sizeChange={setSize}
          total={data?.total ?? 0}
          supportsTruncation
          truncationEnabled={truncationEnabled}
        />
      </div>
      <DatasetItemEditor
        datasetItemId={activeRowId as string}
        datasetId={datasetId}
        columns={columnsData}
        onClose={handleClose}
        isOpen={Boolean(activeRowId)}
        rows={rows}
        setActiveRowId={setActiveRowId}
      />

      <AddEditDatasetItemDialog
        key={resetDialogKeyRef.current}
        datasetId={datasetId}
        open={openDialog}
        setOpen={setOpenDialog}
      />

      <AddDatasetItemSidebar
        datasetId={datasetId}
        open={openAddSidebar}
        setOpen={setOpenAddSidebar}
        columns={columnsData}
      />
    </div>
  );
};

export default DatasetItemsPage;
