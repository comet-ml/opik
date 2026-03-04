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
  DATASET_TYPE,
  Evaluator,
} from "@/types/datasets";
import { ExecutionPolicy } from "@/types/evaluation-suites";
import { Filters } from "@/types/filters";
import {
  COLUMN_DATA_ID,
  COLUMN_ID_ID,
  COLUMN_SELECT_ID,
  COLUMN_TYPE,
  ColumnData,
  DynamicColumn,
  ROW_HEIGHT,
} from "@/types/shared";
import EvaluationSuiteItemPanel from "@/components/pages/EvaluationSuiteItemsPage/EvaluationSuiteItemPanel/EvaluationSuiteItemPanel";
import DatasetItemEditor from "@/components/pages-shared/datasets/DatasetItemEditor/DatasetItemEditor";
import { EvaluatorsCountCell } from "./EvaluatorsCountCell";
import { ExecutionPolicyCell } from "./ExecutionPolicyCell";
import DatasetItemsActionsPanel from "@/components/pages-shared/datasets/DatasetItemsActionsPanel";
import { DatasetItemRowActionsCell } from "@/components/pages-shared/datasets/DatasetItemRowActionsCell";
import DataTableRowHeightSelector from "@/components/shared/DataTableRowHeightSelector/DataTableRowHeightSelector";
import SelectAllBanner from "@/components/shared/SelectAllBanner/SelectAllBanner";
import AddEditDatasetItemDialog from "@/components/pages-shared/datasets/AddEditDatasetItemDialog";
import AddDatasetItemSidebar from "@/components/pages-shared/datasets/AddDatasetItemSidebar";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { Check, Loader2 } from "lucide-react";
import {
  convertColumnDataToColumn,
  injectColumnCallback,
  migrateSelectedColumns,
} from "@/lib/table";
import { buildDocsUrl } from "@/lib/utils";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import AutodetectCell from "@/components/shared/DataTableCells/AutodetectCell";
import IdCell from "@/components/shared/DataTableCells/IdCell";
import ListCell from "@/components/shared/DataTableCells/ListCell";
import TimeCell from "@/components/shared/DataTableCells/TimeCell";
import { mapDynamicColumnTypesToColumnType } from "@/lib/filters";
import {
  generateActionsColumDef,
  generateSelectColumDef,
} from "@/components/shared/DataTable/utils";
import { transformDataColumnFilters } from "@/lib/dataset-items";
import { useEvaluationSuiteItemsWithDraft } from "./hooks/useMergedEvaluationSuiteItems";
import {
  useIsDraftMode,
  useIsAllItemsSelected,
  useSetIsAllItemsSelected,
  useDeletedIds,
} from "@/store/EvaluationSuiteDraftStore";

const getRowId = (d: DatasetItem) => d.id;

export const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID],
  right: [],
};

export const SUITE_DEFAULT_SELECTED_COLUMNS: string[] = [
  "description",
  "last_updated_at",
  "data",
  "expected_behaviors",
  "execution_policy",
];

export const DATASET_DEFAULT_SELECTED_COLUMNS: string[] = [
  COLUMN_ID_ID,
  "created_at",
  "tags",
];

const SUITE_SELECTED_COLUMNS_KEY = "evaluation-suite-items-selected-columns";
const SUITE_SELECTED_COLUMNS_KEY_V2 = `${SUITE_SELECTED_COLUMNS_KEY}-v2`;
const DATASET_SELECTED_COLUMNS_KEY = "dataset-items-selected-columns";
const SUITE_COLUMNS_WIDTH_KEY = "evaluation-suite-items-columns-width";
const DATASET_COLUMNS_WIDTH_KEY = "dataset-items-columns-width";
const SUITE_COLUMNS_ORDER_KEY = "evaluation-suite-items-columns-order";
const DATASET_COLUMNS_ORDER_KEY = "dataset-items-columns-order";
const SUITE_DYNAMIC_COLUMNS_KEY = "evaluation-suite-items-dynamic-columns";
const DATASET_DYNAMIC_COLUMNS_KEY = "dataset-items-dynamic-columns";
const SUITE_PAGINATION_SIZE_KEY = "evaluation-suite-items-pagination-size";
const DATASET_PAGINATION_SIZE_KEY = "dataset-items-pagination-size";
const SUITE_ROW_HEIGHT_KEY = "evaluation-suite-items-row-height";
const DATASET_ROW_HEIGHT_KEY = "dataset-items-row-height";
const POLLING_INTERVAL_MS = 3000;

const DRAFT_STATUS_BORDER_CLASSES: Record<string, string> = {
  [DATASET_ITEM_DRAFT_STATUS.added]: "border-l-2 border-l-green-500",
  [DATASET_ITEM_DRAFT_STATUS.edited]: "border-l-2 border-l-amber-500",
};

interface EvaluationSuiteItemsTabProps {
  datasetId: string;
  datasetName?: string;
  datasetStatus?: DATASET_STATUS;
  datasetType?: DATASET_TYPE;
  suitePolicy?: ExecutionPolicy;
  suiteEvaluators?: Evaluator[];
}

function EvaluationSuiteItemsTab({
  datasetId,
  datasetName,
  datasetStatus,
  datasetType,
  suitePolicy,
  suiteEvaluators,
}: EvaluationSuiteItemsTabProps): React.ReactElement | null {
  const isEvaluationSuite = datasetType === DATASET_TYPE.EVALUATION_SUITE;
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
    localStorageKey: isEvaluationSuite
      ? SUITE_PAGINATION_SIZE_KEY
      : DATASET_PAGINATION_SIZE_KEY,
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
    localStorageKey: isEvaluationSuite
      ? SUITE_ROW_HEIGHT_KEY
      : DATASET_ROW_HEIGHT_KEY,
    queryKey: "height",
    defaultValue: ROW_HEIGHT.small,
    queryParamConfig: StringParam,
    syncQueryWithLocalStorageOnInit: true,
  });

  const resetDialogKeyRef = useRef(0);
  const [openDialog, setOpenDialog] = useState<boolean>(false);
  const [openAddSidebar, setOpenAddSidebar] = useState<boolean>(false);

  const transformedFilters = useMemo(
    () => (filters ? transformDataColumnFilters(filters) : filters),
    [filters],
  );

  const isDraftMode = useIsDraftMode();
  const deletedIds = useDeletedIds();

  const { data, isPending, isPlaceholderData, isFetching } =
    useEvaluationSuiteItemsWithDraft(
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
    isEvaluationSuite
      ? SUITE_SELECTED_COLUMNS_KEY_V2
      : DATASET_SELECTED_COLUMNS_KEY,
    {
      defaultValue: isEvaluationSuite
        ? migrateSelectedColumns(
            SUITE_SELECTED_COLUMNS_KEY,
            SUITE_DEFAULT_SELECTED_COLUMNS,
            ["last_updated_at"],
          )
        : DATASET_DEFAULT_SELECTED_COLUMNS,
    },
  );

  const [columnsOrder, setColumnsOrder] = useLocalStorageState<string[]>(
    isEvaluationSuite ? SUITE_COLUMNS_ORDER_KEY : DATASET_COLUMNS_ORDER_KEY,
    {
      defaultValue: [],
    },
  );

  const [columnsWidth, setColumnsWidth] = useLocalStorageState<
    Record<string, number>
  >(isEvaluationSuite ? SUITE_COLUMNS_WIDTH_KEY : DATASET_COLUMNS_WIDTH_KEY, {
    defaultValue: {},
  });

  const itemLabel = isEvaluationSuite
    ? "evaluation suite items"
    : "dataset items";

  const noDataText = useMemo(() => {
    if (isDraftMode && deletedIds.size > 0 && totalCount !== deletedIds.size) {
      return `All ${itemLabel} on this page have been deleted`;
    }
    return `There are no ${itemLabel} yet`;
  }, [isDraftMode, deletedIds.size, totalCount, itemLabel]);

  const handleSearchChange = useCallback(
    (newSearch: string | null) => {
      setSearch(newSearch);
      if (page !== 1) {
        setPage(1);
      }
    },
    [setSearch, setPage, page],
  );

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

  useDynamicColumnsCache({
    dynamicColumnsKey: isEvaluationSuite
      ? SUITE_DYNAMIC_COLUMNS_KEY
      : DATASET_DYNAMIC_COLUMNS_KEY,
    dynamicColumnsIds,
    setSelectedColumns,
  });

  const columnsData = useMemo((): ColumnData<DatasetItem>[] => {
    const cols: ColumnData<DatasetItem>[] = [
      {
        id: COLUMN_ID_ID,
        label: "ID",
        type: COLUMN_TYPE.string,
        cell: IdCell as never,
      },
    ];

    if (isEvaluationSuite) {
      cols.push(
        {
          id: "description",
          label: "Description",
          type: COLUMN_TYPE.string,
          accessorFn: (row) => row.description ?? "",
        },
        {
          id: "last_updated_at",
          label: "Last updated",
          type: COLUMN_TYPE.time,
          cell: TimeCell as never,
        },
        {
          id: "data",
          label: "Data",
          type: COLUMN_TYPE.dictionary,
          accessorFn: (row) => row.data,
          cell: AutodetectCell as never,
          size: 400,
        },
        {
          id: "expected_behaviors",
          label: "Evaluators",
          type: COLUMN_TYPE.string,
          cell: EvaluatorsCountCell as never,
        },
        {
          id: "execution_policy",
          label: "Execution policy",
          type: COLUMN_TYPE.string,
          cell: ExecutionPolicyCell as never,
        },
      );
    } else {
      // Dynamic per-field columns for legacy datasets
      cols.push(
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
      );
    }

    // Common trailing columns
    cols.push(
      {
        id: "tags",
        label: "Tags",
        type: COLUMN_TYPE.list,
        iconType: "tags",
        accessorFn: (row) => row.tags || [],
        cell: ListCell as never,
      },
      {
        id: "created_at",
        label: "Created",
        type: COLUMN_TYPE.time,
        cell: TimeCell as never,
      },
    );

    // For datasets, last_updated_at goes in the trailing position.
    // For eval suites, it is already included in the branch above.
    if (!isEvaluationSuite) {
      cols.push({
        id: "last_updated_at",
        label: "Last updated",
        type: COLUMN_TYPE.time,
        cell: TimeCell as never,
      });
    }

    cols.push({
      id: "created_by",
      label: "Created by",
      type: COLUMN_TYPE.string,
    });

    return cols;
  }, [isEvaluationSuite, dynamicDatasetColumns]);

  const filtersColumnData = useMemo(
    () => [
      {
        id: "id",
        label: "ID",
        type: COLUMN_TYPE.string,
      },
      ...datasetColumns.map((c) => ({
        id: `${COLUMN_DATA_ID}.${c.name}`,
        label: c.name,
        type: COLUMN_TYPE.string,
      })),
      {
        id: "tags",
        label: "Tags",
        type: COLUMN_TYPE.list,
        iconType: "tags" as const,
      },
    ],
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

  const handleNewDatasetItemClick = useCallback(() => {
    if (data?.total && data.total > 0) {
      setOpenAddSidebar(true);
    } else {
      setOpenDialog(true);
      resetDialogKeyRef.current += 1;
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
            datasetType={datasetType}
            suiteEvaluators={suiteEvaluators}
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
          <Button
            variant="default"
            size="sm"
            onClick={handleNewDatasetItemClick}
          >
            {isEvaluationSuite
              ? "Create evaluation suite item"
              : "Create dataset item"}
          </Button>
        </div>
      </div>
      {isProcessing && (
        <StatusMessage
          icon={Loader2}
          iconClassName="animate-spin"
          title={
            isEvaluationSuite
              ? "Your evaluation suite is still loading"
              : "Your dataset is still loading"
          }
          description={
            isEvaluationSuite
              ? "Some results or counts may update as more data becomes available. You can continue exploring while the full evaluation suite loads."
              : "Some results or counts may update as more data becomes available. You can continue exploring while the full dataset loads."
          }
          className="mb-4"
        />
      )}
      {showSuccessMessage && (
        <StatusMessage
          icon={Check}
          title={
            isEvaluationSuite
              ? "Your evaluation suite fully loaded"
              : "Your dataset fully loaded"
          }
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
              total={totalCount}
              isLoadingTotal={isProcessing}
              disabled={isDraftMode}
            />
          </div>
        </TooltipWrapper>
      </div>
      {isEvaluationSuite ? (
        <EvaluationSuiteItemPanel
          datasetItemId={activeRowId as string}
          datasetId={datasetId}
          columns={datasetColumns}
          onClose={handleClose}
          isOpen={Boolean(activeRowId)}
          rows={rows}
          setActiveRowId={setActiveRowId}
          suitePolicy={suitePolicy}
        />
      ) : (
        <DatasetItemEditor
          datasetItemId={activeRowId as string}
          datasetId={datasetId}
          columns={datasetColumns}
          onClose={handleClose}
          isOpen={Boolean(activeRowId)}
          rows={rows}
          setActiveRowId={setActiveRowId}
        />
      )}

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
}

export default EvaluationSuiteItemsTab;
