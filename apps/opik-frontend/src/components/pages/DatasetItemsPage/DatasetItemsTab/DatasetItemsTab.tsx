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

import DataTable from "@/components/shared/DataTable/DataTable";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import { useDynamicColumnsCache } from "@/hooks/useDynamicColumnsCache";
import useQueryParamAndLocalStorageState from "@/hooks/useQueryParamAndLocalStorageState";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import FiltersButton from "@/components/shared/FiltersButton/FiltersButton";
import useDatasetItemsList from "@/api/datasets/useDatasetItemsList";
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
import SelectAllBanner from "@/components/shared/SelectAllBanner/SelectAllBanner";
import AddEditDatasetItemDialog from "@/components/pages/DatasetItemsPage/AddEditDatasetItemDialog";
import AddDatasetItemSidebar from "@/components/pages/DatasetItemsPage/AddDatasetItemSidebar";
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

interface DatasetItemsTabProps {
  datasetId: string;
  datasetName?: string;
}

const DatasetItemsTab: React.FC<DatasetItemsTabProps> = ({
  datasetId,
  datasetName,
}) => {
  const truncationEnabled = false;

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
  const [isAllItemsSelected, setIsAllItemsSelected] = useState(false);

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

  const { data, isPending, isPlaceholderData, isFetching } =
    useDatasetItemsList(
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
  const totalCount = data?.total ?? 0;

  const datasetColumns = useMemo(
    () =>
      (data?.columns ?? []).sort((c1, c2) => c1.name.localeCompare(c2.name)),
    [data?.columns],
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

  const { refetch: refetchAllItemsForExport } = useDatasetItemsList(
    {
      datasetId,
      filters,
      page: 1,
      size: totalCount || 1,
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

  const handleRowSelectionChange: typeof setRowSelection = useCallback(
    (updaterOrValue) => {
      setRowSelection((prev) => {
        const next =
          typeof updaterOrValue === "function"
            ? updaterOrValue(prev)
            : updaterOrValue;

        // Reset isAllItemsSelected if selection count decreases (row deselected)
        if (
          isAllItemsSelected &&
          Object.keys(next).length < Object.keys(prev).length
        ) {
          setIsAllItemsSelected(false);
        }

        return next;
      });
    },
    [isAllItemsSelected],
  );

  const effectiveIsAllItemsSelected = useMemo(() => {
    return (
      isAllItemsSelected &&
      selectedRows.length === rows.length &&
      rows.length > 0
    );
  }, [isAllItemsSelected, selectedRows.length, rows.length]);

  const getDataForExport = useCallback(async (): Promise<DatasetItem[]> => {
    const result = effectiveIsAllItemsSelected
      ? await refetchAllItemsForExport()
      : await refetchExportData();

    if (result.error) {
      throw result.error;
    }

    if (!result.data?.content) {
      throw new Error("Failed to fetch data");
    }

    if (effectiveIsAllItemsSelected) {
      return result.data.content;
    }

    const selectedIds = Object.keys(rowSelection);

    return result.data.content.filter((row) => selectedIds.includes(row.id));
  }, [
    refetchExportData,
    refetchAllItemsForExport,
    rowSelection,
    effectiveIsAllItemsSelected,
  ]);

  const dynamicDatasetColumns = useMemo(() => {
    return datasetColumns.map<DynamicColumn>((c) => ({
      id: `${DATASET_ITEM_DATA_PREFIX}.${c.name}`,
      label: c.name,
      columnType: mapDynamicColumnTypesToColumnType(c.types),
    }));
  }, [datasetColumns]);

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
      iconType: "tags",
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
        iconType: "tags" as const,
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

  const handleClearSelection = useCallback(() => {
    setRowSelection({});
    setIsAllItemsSelected(false);
  }, []);

  const showSelectAllBanner =
    selectedRows.length > 0 &&
    selectedRows.length === rows.length &&
    selectedRows.length < totalCount;

  if (isPending) {
    return null;
  }

  return (
    <>
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
            datasetName={datasetName ?? ""}
            columnsToExport={columnsToExport}
            dynamicColumns={dynamicColumnsIds}
            isAllItemsSelected={effectiveIsAllItemsSelected}
            filters={filters}
            search={search ?? ""}
            totalCount={totalCount}
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
      {showSelectAllBanner && (
        <SelectAllBanner
          selectedCount={isAllItemsSelected ? totalCount : selectedRows.length}
          totalCount={totalCount}
          onSelectAll={() => setIsAllItemsSelected(true)}
          onClearSelection={handleClearSelection}
        />
      )}
      <DataTable
        columns={columns}
        data={rows}
        onRowClick={handleRowClick}
        activeRowId={activeRowId ?? ""}
        resizeConfig={resizeConfig}
        showLoadingOverlay={isPlaceholderData && isFetching}
        selectionConfig={{
          rowSelection,
          setRowSelection: handleRowSelectionChange,
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
        columns={datasetColumns}
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
        columns={datasetColumns}
      />
    </>
  );
};

export default DatasetItemsTab;
