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
import { Check, Loader2 } from "lucide-react";

import DataTable from "@/shared/DataTable/DataTable";
import DataTablePagination from "@/shared/DataTablePagination/DataTablePagination";
import { useDynamicColumnsCache } from "@/hooks/useDynamicColumnsCache";
import useQueryParamAndLocalStorageState from "@/hooks/useQueryParamAndLocalStorageState";
import useDatasetLoadingStatus from "@/hooks/useDatasetLoadingStatus";
import ColumnsButton from "@/shared/ColumnsButton/ColumnsButton";
import SearchInput from "@/shared/SearchInput/SearchInput";
import FiltersButton from "@/shared/FiltersButton/FiltersButton";
import useDatasetItemsList from "@/api/datasets/useDatasetItemsList";
import StatusMessage from "@/shared/StatusMessage/StatusMessage";
import {
  DatasetItem,
  DatasetItemColumn,
  DatasetItemWithDraft,
  DATASET_ITEM_DRAFT_STATUS,
  DATASET_STATUS,
} from "@/types/datasets";
import { Filters } from "@/types/filters";
import {
  COLUMN_DATA_ID,
  COLUMN_SELECT_ID,
  ColumnData,
  ROW_HEIGHT,
} from "@/types/shared";
import DatasetItemsActionsPanel from "@/v2/pages-shared/datasets/DatasetItemsActionsPanel";
import { DatasetItemRowActionsCell } from "@/v2/pages-shared/datasets/DatasetItemRowActionsCell";
import DataTableRowHeightSelector from "@/shared/DataTableRowHeightSelector/DataTableRowHeightSelector";
import SelectAllBanner from "@/shared/SelectAllBanner/SelectAllBanner";
import { Separator } from "@/ui/separator";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { useObserveResizeNode } from "@/hooks/useObserveResizeNode";
import ExpandableSearchInput from "@/shared/ExpandableSearchInput/ExpandableSearchInput";
import {
  convertColumnDataToColumn,
  injectColumnCallback,
  migrateSelectedColumns,
} from "@/lib/table";
import DataTableEmptyContent from "@/shared/DataTableNoData/DataTableEmptyContent";
import {
  buildDatasetFilterColumns,
  mapDynamicColumnTypesToColumnType,
} from "@/lib/filters";
import {
  generateActionsColumDef,
  generateSelectColumDef,
} from "@/shared/DataTable/utils";
import { transformDataColumnFilters } from "@/lib/dataset-items";
import { useDatasetItemsWithDraft } from "./hooks/useMergedDatasetItems";
import { usePermissions } from "@/contexts/PermissionsContext";
import {
  useIsDraftMode,
  useIsAllItemsSelected,
  useSetIsAllItemsSelected,
  useDeletedIds,
} from "@/store/TestSuiteDraftStore";
import { COLUMN_ID_ID } from "@/types/shared";
import { DynamicColumn } from "@/types/shared";

const getRowId = (d: DatasetItem) => d.id;

export const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID],
  right: [],
};

export interface StorageKeysConfig {
  selectedColumnsKey: string;
  selectedColumnsMigrationKey?: string;
  migrationNewColumns?: string[];
  columnsWidthKey: string;
  columnsOrderKey: string;
  dynamicColumnsKey: string;
  paginationSizeKey: string;
  rowHeightKey: string;
}

export interface EditPanelRenderProps {
  datasetItemId: string;
  datasetId: string;
  columns: DatasetItemColumn[];
  onClose: () => void;
  isOpen: boolean;
  rows: DatasetItemWithDraft[];
  setActiveRowId: (id: string) => void;
}

export interface AddPanelRenderProps {
  open: boolean;
  onClose: () => void;
  columns: DatasetItemColumn[];
}

export interface AddDialogRenderProps {
  key: number;
  datasetId: string;
  open: boolean;
  setOpen: (open: boolean) => void;
}

export interface ExpansionDialogRenderProps {
  open: boolean;
  setOpen: (open: boolean) => void;
}

interface DatasetItemsTabProps {
  datasetId: string;
  datasetName?: string;
  datasetStatus?: DATASET_STATUS;
  storageKeys: StorageKeysConfig;
  defaultSelectedColumns: string[];
  entityName: string;
  buildColumns: (
    datasetColumns: DatasetItemColumn[],
    dynamicDatasetColumns: DynamicColumn[],
  ) => ColumnData<DatasetItem>[];
  renderEditPanel: (props: EditPanelRenderProps) => React.ReactNode;
  onAddItem: () => void;
  itemName: string;
}

const POLLING_INTERVAL_MS = 3000;
const COMPACT_TOOLBAR_BREAKPOINT = 850;

const DRAFT_STATUS_BORDER_CLASSES: Record<string, string> = {
  [DATASET_ITEM_DRAFT_STATUS.added]: "border-l-2 border-l-green-500",
  [DATASET_ITEM_DRAFT_STATUS.edited]: "border-l-2 border-l-amber-500",
};

function DatasetItemsTab({
  datasetId,
  datasetName,
  datasetStatus,
  storageKeys,
  defaultSelectedColumns,
  entityName,
  buildColumns,
  renderEditPanel,
  onAddItem,
  itemName,
}: DatasetItemsTabProps): React.ReactElement | null {
  const {
    permissions: { canEditDatasets },
  } = usePermissions();

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
    localStorageKey: storageKeys.paginationSizeKey,
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
    localStorageKey: storageKeys.rowHeightKey,
    queryKey: "height",
    defaultValue: ROW_HEIGHT.small,
    queryParamConfig: StringParam,
    syncQueryWithLocalStorageOnInit: true,
  });

  const [isCompactToolbar, setIsCompactToolbar] = useState(false);
  const [isSearchExpanded, setIsSearchExpanded] = useState(Boolean(search));
  const isCollapsingRef = useRef(false);
  const handleToolbarResize = useCallback((node: HTMLDivElement) => {
    setIsCompactToolbar(node.clientWidth < COMPACT_TOOLBAR_BREAKPOINT);
  }, []);
  const { ref: toolbarRef } =
    useObserveResizeNode<HTMLDivElement>(handleToolbarResize);

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
        search: search ?? "",
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
      search: search ?? "",
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
      search: search ?? "",
      truncate: false,
    },
    {
      enabled: false,
    },
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
      defaultValue: storageKeys.selectedColumnsMigrationKey
        ? migrateSelectedColumns(
            storageKeys.selectedColumnsMigrationKey,
            defaultSelectedColumns,
            storageKeys.migrationNewColumns ?? [],
          )
        : defaultSelectedColumns,
    },
  );

  useDynamicColumnsCache({
    dynamicColumnsKey: storageKeys.dynamicColumnsKey,
    dynamicColumnsIds,
    setSelectedColumns,
  });

  const columnsData = useMemo(
    () => buildColumns(datasetColumns, dynamicDatasetColumns),
    [buildColumns, datasetColumns, dynamicDatasetColumns],
  );

  const [columnsOrder, setColumnsOrder] = useLocalStorageState<string[]>(
    storageKeys.columnsOrderKey,
    {
      defaultValue: [],
    },
  );

  const [columnsWidth, setColumnsWidth] = useLocalStorageState<
    Record<string, number>
  >(storageKeys.columnsWidthKey, {
    defaultValue: {},
  });

  const noDataText = useMemo(() => {
    if (isDraftMode && deletedIds.size > 0) {
      return `All ${itemName}s on this page have been deleted`;
    }
    return `No ${itemName}s yet`;
  }, [isDraftMode, deletedIds.size, itemName]);

  const handleSearchChange = useCallback(
    (newSearch: string | null) => {
      setSearch(newSearch);
      if (page !== 1) {
        setPage(1);
      }
    },
    [setSearch, setPage, page],
  );

  // ExpandableSearchInput calls onExpandedChange(false) before onChange("") on collapse.
  // We use this ref to skip the clearing onChange so the search filter stays active.
  const handleCompactSearchChange = useCallback(
    (value: string) => {
      if (isCollapsingRef.current && value === "") {
        isCollapsingRef.current = false;
        return;
      }
      handleSearchChange(value || null);
    },
    [handleSearchChange],
  );

  const handleSearchExpandedChange = useCallback((expanded: boolean) => {
    setIsSearchExpanded(expanded);
    if (!expanded) {
      isCollapsingRef.current = true;
    }
  }, []);

  const selectedRows: DatasetItem[] = useMemo(
    () => rows.filter((row) => rowSelection[row.id]),
    [rowSelection, rows],
  );

  const handleRowSelectionChange: typeof setRowSelection = useCallback(
    (updaterOrValue) => {
      setRowSelection((prev) => {
        const next =
          typeof updaterOrValue === "function"
            ? updaterOrValue(prev)
            : updaterOrValue;

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

  const effectiveIsAllItemsSelected = useMemo(
    () =>
      isAllItemsSelected &&
      selectedRows.length === rows.length &&
      rows.length > 0,
    [isAllItemsSelected, selectedRows.length, rows.length],
  );

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

  const filtersColumnData = useMemo(
    () => buildDatasetFilterColumns(datasetColumns, true),
    [datasetColumns],
  );

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

      return DRAFT_STATUS_BORDER_CLASSES[draftStatus] ?? "border-l-2";
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
      ...(canEditDatasets
        ? injectColumnCallback(convertedColumns, COLUMN_ID_ID, handleRowClick)
        : convertedColumns),
      ...(canEditDatasets
        ? [
            generateActionsColumDef({
              cell: DatasetItemRowActionsCell,
              customMeta: { setActiveRowId },
            }),
          ]
        : []),
    ];
  }, [
    columnsData,
    columnsOrder,
    selectedColumns,
    canEditDatasets,
    getDraftStatusBorderClass,
    handleRowClick,
    setActiveRowId,
  ]);

  const columnsToExport = useMemo(
    () =>
      columns
        .map((c) => get(c, "accessorKey", ""))
        .filter(
          (c) =>
            c !== COLUMN_SELECT_ID &&
            (selectedColumns.includes(c) ||
              (DEFAULT_COLUMN_PINNING.left || []).includes(c)),
        ),
    [columns, selectedColumns],
  );

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

  const isTableLoading = isPending || (isPlaceholderData && rows.length === 0);

  return (
    <>
      <div
        ref={toolbarRef}
        className="relative mb-4 flex items-center justify-between gap-8"
      >
        <div className="flex items-center gap-2">
          {isCompactToolbar ? (
            <ExpandableSearchInput
              value={search ?? ""}
              placeholder="Search"
              onChange={handleCompactSearchChange}
              disabled={isDraftMode}
              buttonVariant="outline"
              tooltip={isDraftMode ? "Save changes to search" : "Search items"}
              onExpandedChange={handleSearchExpandedChange}
              className={
                isSearchExpanded
                  ? "absolute inset-x-0 top-0 z-10 bg-soft-background"
                  : ""
              }
            />
          ) : (
            <TooltipWrapper
              content={isDraftMode ? "Save changes to search" : undefined}
            >
              <div>
                <SearchInput
                  searchText={search ?? ""}
                  setSearchText={handleSearchChange}
                  placeholder="Search"
                  className="w-[320px]"
                  dimension="sm"
                  disabled={isDraftMode}
                />
              </div>
            </TooltipWrapper>
          )}
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
            entityName={entityName}
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
          />
        </div>
      </div>
      {isProcessing && (
        <StatusMessage
          icon={Loader2}
          iconClassName="animate-spin"
          title={`Your ${entityName} is still loading`}
          description={`Some results or counts may update as more data becomes available. You can continue exploring while the full ${entityName} loads.`}
          className="mb-4"
        />
      )}
      {showSuccessMessage && (
        <StatusMessage
          icon={Check}
          title={`Your ${entityName} fully loaded`}
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
        onRowClick={canEditDatasets ? handleRowClick : undefined}
        activeRowId={activeRowId ?? ""}
        resizeConfig={resizeConfig}
        showSkeleton={isTableLoading}
        showLoadingOverlay={!isTableLoading && isPlaceholderData && isFetching}
        selectionConfig={{
          rowSelection,
          setRowSelection: handleRowSelectionChange,
        }}
        getRowId={getRowId}
        rowHeight={height as ROW_HEIGHT}
        columnPinning={DEFAULT_COLUMN_PINNING}
        noData={
          <DataTableEmptyContent
            title={noDataText}
            description={
              isDraftMode && deletedIds.size > 0
                ? ""
                : `Add ${itemName}s to run evaluations and measure performance.`
            }
          >
            {!(isDraftMode && deletedIds.size > 0) && (
              <button
                onClick={onAddItem}
                className="comet-body-s underline underline-offset-4 hover:text-primary"
                data-testid="dataset-items-empty-add-button"
              >
                Add new {itemName}
              </button>
            )}
          </DataTableEmptyContent>
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
              total={totalCount}
              isLoadingTotal={isProcessing}
              disabled={isDraftMode}
            />
          </div>
        </TooltipWrapper>
      </div>
      {renderEditPanel({
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
}

export default DatasetItemsTab;
