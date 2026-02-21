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
import useDatasetLoadingStatus from "@/hooks/useDatasetLoadingStatus";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import FiltersButton from "@/components/shared/FiltersButton/FiltersButton";
import useDatasetItemsList from "@/api/datasets/useDatasetItemsList";
import StatusMessage from "@/components/shared/StatusMessage/StatusMessage";
import {
  DatasetItem,
  DatasetItemWithDraft,
  DATASET_ITEM_DRAFT_STATUS,
  DATASET_STATUS,
} from "@/types/datasets";
import { Filter, Filters } from "@/types/filters";
import {
  COLUMN_DATA_ID,
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
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { Check, Loader2 } from "lucide-react";
import { convertColumnDataToColumn, injectColumnCallback } from "@/lib/table";
import { buildDocsUrl } from "@/lib/utils";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import AutodetectCell from "@/components/shared/DataTableCells/AutodetectCell";
import IdCell from "@/components/shared/DataTableCells/IdCell";
import ListCell from "@/components/shared/DataTableCells/ListCell";
import { formatDate } from "@/lib/date";
import { mapDynamicColumnTypesToColumnType } from "@/lib/filters";
import {
  generateActionsColumDef,
  generateSelectColumDef,
} from "@/components/shared/DataTable/utils";
import { DATASET_ITEM_DATA_PREFIX } from "@/constants/datasets";
import { useDatasetItemsWithDraft } from "./hooks/useMergedDatasetItems";
import {
  useIsDraftMode,
  useIsAllItemsSelected,
  useSetIsAllItemsSelected,
  useDeletedIds,
} from "@/store/DatasetDraftStore";

/**
 * Transform data column filters from "data.columnName" format to backend format.
 * This converts field="data.columnName" to field="data" with key="columnName".
 * This transformation is specific to DatasetItemsPage and should not be in generic filter processing.
 */
const transformDataColumnFilters = (filters: Filters): Filters => {
  const dataFieldPrefix = `${COLUMN_DATA_ID}.`;

  return filters.map((filter: Filter) => {
    if (filter.field.startsWith(dataFieldPrefix)) {
      const columnKey = filter.field.slice(dataFieldPrefix.length);
      return {
        ...filter,
        field: COLUMN_DATA_ID,
        key: columnKey,
      };
    }
    return filter;
  });
};

const getRowId = (d: DatasetItem) => d.id;

export const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID],
  right: [],
};

export const DEFAULT_SELECTED_COLUMNS: string[] = [
  COLUMN_ID_ID,
  "created_at",
  "tags",
];

const SELECTED_COLUMNS_KEY = "dataset-items-selected-columns";
const COLUMNS_WIDTH_KEY = "dataset-items-columns-width";
const COLUMNS_ORDER_KEY = "dataset-items-columns-order";
const DYNAMIC_COLUMNS_KEY = "dataset-items-dynamic-columns";
const PAGINATION_SIZE_KEY = "dataset-items-pagination-size";
const ROW_HEIGHT_KEY = "dataset-items-row-height";
const POLLING_INTERVAL_MS = 3000;

interface DatasetItemsTabProps {
  datasetId: string;
  datasetName?: string;
  datasetStatus?: DATASET_STATUS;
}

const DatasetItemsTab: React.FC<DatasetItemsTabProps> = ({
  datasetId,
  datasetName,
  datasetStatus,
}) => {
  const { isProcessing, showSuccessMessage } = useDatasetLoadingStatus({
    datasetStatus,
  });

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
  const isAllItemsSelected = useIsAllItemsSelected();
  const setIsAllItemsSelected = useSetIsAllItemsSelected();

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

  // Transform data column filters before passing to API
  const transformedFilters = useMemo(
    () => (filters ? transformDataColumnFilters(filters) : filters),
    [filters],
  );

  const isDraftMode = useIsDraftMode();
  const deletedIds = useDeletedIds();

  const { data, isPending, isPlaceholderData, isFetching } =
    useDatasetItemsWithDraft(
      {
        datasetId,
        filters: transformedFilters,
        page: page as number,
        size: size as number,
        search: search!,
        truncate: false,
      },
      {
        placeholderData: keepPreviousData,
        refetchInterval: isProcessing ? POLLING_INTERVAL_MS : false,
      },
    );
  const totalCount = data?.total ?? 0;

  const rows = useMemo(() => data?.content ?? [], [data?.content]);

  const datasetColumns = useMemo(
    () =>
      (data?.columns ?? []).sort((c1, c2) => c1.name.localeCompare(c2.name)),
    [data?.columns],
  );

  const { refetch: refetchExportData } = useDatasetItemsList(
    {
      datasetId,
      filters: transformedFilters,
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
      filters: transformedFilters,
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

  const noDataText = useMemo(() => {
    if (isDraftMode && deletedIds.size > 0 && totalCount !== deletedIds.size) {
      return "All dataset items on this page have been deleted";
    }
    return "There are no dataset items yet";
  }, [isDraftMode, deletedIds.size, totalCount]);

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
    [isAllItemsSelected, setIsAllItemsSelected],
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
    const retVal: ColumnData<DatasetItem>[] = [
      {
        id: COLUMN_ID_ID,
        label: "ID",
        type: COLUMN_TYPE.string,
        accessorFn: (row) => row.dataset_item_id ?? row.id,
        cell: IdCell as never,
      },
      ...dynamicDatasetColumns.map(
        ({ label, id, columnType }) =>
          ({
            id,
            label,
            type: columnType,
            accessorFn: (row) => get(row, ["data", label], ""),
            cell: AutodetectCell as never,
          }) as ColumnData<DatasetItem>,
      ),
    ];

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
    // Add each data column as a separate filter option with field prefix "data."
    // This will be transformed to field="data" and key=columnName when processing
    const dataFilterColumns = datasetColumns.map((c) => ({
      id: `${COLUMN_DATA_ID}.${c.name}`,
      label: c.name,
      type: COLUMN_TYPE.string,
    }));

    return [
      {
        id: "dataset_item_id",
        label: "ID",
        type: COLUMN_TYPE.string,
      },
      ...dataFilterColumns,
      {
        id: "tags",
        label: "Tags",
        type: COLUMN_TYPE.list,
        iconType: "tags" as const,
      },
    ];
  }, [datasetColumns]);

  const handleRowClick = useCallback(
    (row: DatasetItem) => {
      setActiveRowId((state) => (row.id === state ? "" : row.id));
    },
    [setActiveRowId],
  );

  const getDraftStatusBorderClass = useCallback(
    (item: DatasetItemWithDraft): string => {
      const { draftStatus } = item;

      if (!draftStatus || draftStatus === DATASET_ITEM_DRAFT_STATUS.unchanged) {
        return "border-l-2 border-l-transparent";
      }

      const DRAFT_STATUS_STYLES: Record<string, string> = {
        [DATASET_ITEM_DRAFT_STATUS.added]: "border-l-2 border-l-green-500",
        [DATASET_ITEM_DRAFT_STATUS.edited]: "border-l-2 border-l-amber-500",
      };

      return DRAFT_STATUS_STYLES[draftStatus] ?? "border-l-2";
    },
    [],
  );

  const columns = useMemo(() => {
    const convertedColumns = convertColumnDataToColumn<
      DatasetItem,
      DatasetItem
    >(columnsData, {
      columnsOrder,
      selectedColumns,
    });

    return [
      generateSelectColumDef<DatasetItem>({
        cellClassName: (context) => {
          const item = context.row.original as DatasetItemWithDraft;
          return getDraftStatusBorderClass(item);
        },
      }),
      ...injectColumnCallback(convertedColumns, COLUMN_ID_ID, handleRowClick),
      generateActionsColumDef({
        cell: DatasetItemRowActionsCell,
      }),
    ];
  }, [
    columnsData,
    columnsOrder,
    selectedColumns,
    getDraftStatusBorderClass,
    handleRowClick,
  ]);

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
  }, [setIsAllItemsSelected]);

  const showSelectAllBanner =
    !isDraftMode &&
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
          <TooltipWrapper
            content={isDraftMode ? "Save changes to search" : undefined}
          >
            <div>
              <SearchInput
                searchText={search!}
                setSearchText={handleSearchChange}
                placeholder="Search"
                className="w-[320px]"
                dimension="sm"
                disabled={isDraftMode}
              />
            </div>
          </TooltipWrapper>
          <TooltipWrapper
            content={isDraftMode ? "Save changes to filter" : undefined}
          >
            <div>
              <FiltersButton
                columns={filtersColumnData}
                filters={filters}
                onChange={setFilters}
                disabled={isDraftMode}
                layout="icon"
              />
            </div>
          </TooltipWrapper>
        </div>
        <div className="flex items-center gap-2">
          <DatasetItemsActionsPanel
            getDataForExport={getDataForExport}
            selectedDatasetItems={selectedRows}
            datasetId={datasetId}
            datasetName={datasetName ?? ""}
            columnsToExport={columnsToExport}
            dynamicColumns={dynamicColumnsIds}
            filters={filters}
            search={search ?? ""}
            totalCount={totalCount}
            isDraftMode={isDraftMode}
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
      {isProcessing && (
        <StatusMessage
          icon={Loader2}
          iconClassName="animate-spin"
          title="Your dataset is still loading"
          description="Some results or counts may update as more data becomes available. You can continue exploring while the full dataset loads."
          className="mb-4"
        />
      )}
      {showSuccessMessage && (
        <StatusMessage
          icon={Check}
          title="Your dataset fully loaded"
          description="All items are now available."
          className="mb-4"
        />
      )}
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
      <div className="flex justify-end py-4">
        <TooltipWrapper
          content={isDraftMode ? "Save changes to navigate pages" : undefined}
        >
          <div>
            <DataTablePagination
              page={page as number}
              pageChange={setPage}
              size={size as number}
              sizeChange={setSize}
              total={data?.total ?? 0}
              isLoadingTotal={isProcessing}
              disabled={isDraftMode}
            />
          </div>
        </TooltipWrapper>
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
        open={openAddSidebar}
        setOpen={setOpenAddSidebar}
        columns={datasetColumns}
      />
    </>
  );
};

export default DatasetItemsTab;
