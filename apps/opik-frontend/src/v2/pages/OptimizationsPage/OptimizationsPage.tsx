import React, { useCallback, useMemo, useRef, useState } from "react";
import useLocalStorageState from "use-local-storage-state";
import { RowSelectionState } from "@tanstack/react-table";
import { useNavigate } from "@tanstack/react-router";
import {
  JsonParam,
  NumberParam,
  StringParam,
  useQueryParam,
} from "use-query-params";
import DataTable from "@/shared/DataTable/DataTable";
import DataTablePagination from "@/shared/DataTablePagination/DataTablePagination";
import DataTableNoMatchingData from "@/shared/DataTableNoData/DataTableNoMatchingData";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";
import { COLUMN_DATASET_ID, COLUMN_TYPE } from "@/types/shared";
import { Filter } from "@/types/filters";
import { Optimization } from "@/types/optimizations";
import { convertColumnDataToColumn } from "@/lib/table";
import useProjectDatasetsList from "@/api/datasets/useProjectDatasetsList";
import useFilterChips from "@/shared/filter-chips/hooks/useFilterChips";
import { ChipDefinition } from "@/shared/filter-chips/types";
import { OPTIMIZATION_STATUS_OPTIONS } from "@/lib/optimizations";
import AddOptimizationDialog from "@/v2/pages/OptimizationsPage/AddOptimizationDialog/AddOptimizationDialog";
import OptimizationRowActionsCell from "@/v2/pages/OptimizationsPage/OptimizationRowActionsCell";
import {
  generateActionsColumDef,
  generateSelectColumDef,
} from "@/shared/DataTable/utils";
import OptimizationsEmptyState from "@/v2/pages/OptimizationsPage/OptimizationsEmptyState";
import OptimizationsToolbar from "@/v2/pages/OptimizationsPage/OptimizationsToolbar";
import PageEmptyState from "@/shared/PageEmptyState/PageEmptyState";
import { buildDocsUrl } from "@/v2/lib/utils";
import emptyOptStudioLightUrl from "/images/empty-optimization-studio-light.svg";
import emptyOptStudioDarkUrl from "/images/empty-optimization-studio-dark.svg";
import StudioTemplates from "@/v2/pages-shared/optimizations/StudioTemplates";
import { Separator } from "@/ui/separator";
import { useOptimizationsView } from "@/hooks/useOptimizationsView";
import { useOptimizationsExistence } from "@/hooks/useOptimizationsExistence";
import { usePermissions } from "@/contexts/PermissionsContext";
import {
  DEFAULT_COLUMNS,
  DEFAULT_COLUMNS_ORDER,
  DEFAULT_SELECTED_COLUMNS,
  SELECTED_COLUMNS_KEY,
  COLUMNS_WIDTH_KEY,
  COLUMNS_ORDER_KEY,
} from "@/v2/pages/OptimizationsPage/OptimizationsColumns";

const selectColumn = generateSelectColumDef();

const actionsColumn = generateActionsColumDef({
  cell: OptimizationRowActionsCell,
});

const OptimizationsPage: React.FunctionComponent = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const activeProjectId = useActiveProjectId();
  const navigate = useNavigate();
  const resetDialogKeyRef = useRef(0);
  const [openDialog, setOpenDialog] = useState<boolean>(false);

  const {
    permissions: {
      canViewDatasets,
      canDeleteOptimizationRuns,
      canUseOptimizationStudio,
    },
  } = usePermissions();

  const [search = "", setSearch] = useQueryParam("search", StringParam, {
    updateType: "replaceIn",
  });

  const [filters = []] = useQueryParam("filters", JsonParam, {
    updateType: "replaceIn",
  });

  const [page = 1, setPage] = useQueryParam("page", NumberParam, {
    updateType: "replaceIn",
  });

  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});

  const datasetId = useMemo(
    () =>
      filters.find((f: Filter) => f.field === COLUMN_DATASET_ID)?.value || "",
    [filters],
  );

  // Dataset is sent via the dedicated `dataset_id` param; the rest (status)
  // go through the list filter builder.
  const listFilters = useMemo(
    () => filters.filter((f: Filter) => f.field !== COLUMN_DATASET_ID),
    [filters],
  );

  const { data: datasetsData } = useProjectDatasetsList(
    {
      projectId: activeProjectId!,
      page: 1,
      size: 1000,
    },
    {
      enabled: canViewDatasets && !!activeProjectId,
    },
  );

  const datasetOptions = useMemo(
    () =>
      (datasetsData?.content || []).map((dataset) => ({
        label: dataset.name,
        value: dataset.id,
      })),
    [datasetsData?.content],
  );

  const chipDefinitions = useMemo<ChipDefinition[]>(() => {
    const definitions: ChipDefinition[] = [];
    if (canViewDatasets) {
      definitions.push({
        id: COLUMN_DATASET_ID,
        field: COLUMN_DATASET_ID,
        label: "Test suite",
        kind: "single-select",
        columnType: COLUMN_TYPE.string,
        operator: "=",
        options: datasetOptions,
      });
    }
    definitions.push({
      id: "status",
      field: "status",
      label: "Status",
      kind: "single-select",
      columnType: COLUMN_TYPE.string,
      operator: "=",
      options: OPTIMIZATION_STATUS_OPTIONS,
    });
    return definitions;
  }, [canViewDatasets, datasetOptions]);

  const {
    chipsPinned,
    chipsUnpinned,
    values: chipValues,
    applyValue: applyChipValue,
    clearValue: clearChipValue,
    clearAll: clearAllChips,
    pinChip,
    unpinChip,
    managerOpen: chipManagerOpen,
    setManagerOpen: setChipManagerOpen,
    openChipId,
    setOpenChipId,
  } = useFilterChips({
    tableId: "optimizations",
    urlKey: "filters",
    definitions: chipDefinitions,
    defaultPinned: [...(canViewDatasets ? [COLUMN_DATASET_ID] : []), "status"],
    onChange: () => setPage(1),
  });

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

  const {
    optimizations,
    total,
    selectedRows,
    isPending,
    isPlaceholderData,
    isFetching,
    refetch,
    pageSize,
  } = useOptimizationsView({
    workspaceName,
    projectId: activeProjectId ?? undefined,
    datasetId,
    filters: listFilters,
    search: search || "",
    page: page || 1,
    rowSelection,
    pollWhileInProgress: true,
  });

  const { isEmpty, isPending: isExistencePending } = useOptimizationsExistence({
    workspaceName,
    projectId: activeProjectId ?? undefined,
  });

  const hasOldTypeOptimizations = useMemo(
    () =>
      optimizations.some(
        (opt) => !opt.experiment_scores || opt.experiment_scores.length === 0,
      ),
    [optimizations],
  );

  const visibleColumns = useMemo(
    () =>
      hasOldTypeOptimizations
        ? DEFAULT_COLUMNS
        : DEFAULT_COLUMNS.filter((c) => c.id !== "accuracy"),
    [hasOldTypeOptimizations],
  );

  const defaultColumns = useMemo(
    () =>
      convertColumnDataToColumn(visibleColumns, {
        columnsOrder,
        selectedColumns,
      }),
    [visibleColumns, columnsOrder, selectedColumns],
  );

  const columns = useMemo(() => {
    if (canDeleteOptimizationRuns) {
      return [selectColumn, ...defaultColumns, actionsColumn];
    }
    return [selectColumn, ...defaultColumns];
  }, [canDeleteOptimizationRuns, defaultColumns]);

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      columnSizing: columnsWidth,
      onColumnResize: setColumnsWidth,
    }),
    [columnsWidth, setColumnsWidth],
  );

  const handleRowClick = useCallback(
    (row: Optimization) => {
      navigate({
        to: "/$workspaceName/projects/$projectId/optimizations/$optimizationId",
        params: {
          optimizationId: row.id,
          workspaceName,
          projectId: activeProjectId!,
        },
      });
    },
    [navigate, workspaceName, activeProjectId],
  );

  const handleNewOptimizationClick = useCallback(() => {
    setOpenDialog(true);
    resetDialogKeyRef.current = resetDialogKeyRef.current + 1;
  }, []);

  const isTableLoading =
    isExistencePending ||
    isPending ||
    (isPlaceholderData && optimizations.length === 0);

  const handleClearFilters = useCallback(() => {
    setSearch(undefined);
    clearAllChips();
  }, [setSearch, clearAllChips]);

  return (
    <div className="flex min-h-full flex-col pt-4">
      <div className="mb-4 flex min-h-7 items-center justify-between">
        <h1 className="comet-body-accented truncate break-words">
          Optimization runs
        </h1>
      </div>
      {isEmpty ? (
        canUseOptimizationStudio ? (
          <OptimizationsEmptyState
            onOptimizeViaSdkClick={handleNewOptimizationClick}
          />
        ) : (
          <PageEmptyState
            lightImageUrl={emptyOptStudioLightUrl}
            darkImageUrl={emptyOptStudioDarkUrl}
            title="No optimization runs yet"
            description="Try different prompt versions and see what performs best. Optimization runs help you improve accuracy, consistency, and user experience."
            docsUrl={buildDocsUrl(
              "/development/optimization-runs/optimization_studio",
            )}
          />
        )
      ) : (
        <>
          {canUseOptimizationStudio && !isTableLoading && (
            <>
              <StudioTemplates
                onOptimizeViaSdkClick={handleNewOptimizationClick}
              />
              <Separator className="my-3" />
            </>
          )}
          <div>
            <OptimizationsToolbar
              search={search!}
              onSearchChange={setSearch}
              filterChips={{
                chipsPinned,
                chipsUnpinned,
                values: chipValues,
                managerOpen: chipManagerOpen,
                onManagerOpenChange: setChipManagerOpen,
                onApplyValue: applyChipValue,
                onClearValue: clearChipValue,
                onPinChip: pinChip,
                onUnpinChip: unpinChip,
                onClearAll: clearAllChips,
                openChipId,
                onOpenChipIdChange: setOpenChipId,
              }}
              canDeleteOptimizationRuns={canDeleteOptimizationRuns}
              selectedRows={selectedRows}
              isFetching={isFetching}
              onRefresh={() => refetch()}
              columns={visibleColumns}
              selectedColumns={selectedColumns}
              onSelectedColumnsChange={setSelectedColumns}
              columnsOrder={columnsOrder}
              onColumnsOrderChange={setColumnsOrder}
            />
            <DataTable
              columns={columns as never}
              data={optimizations as never}
              getRowId={(row: Optimization) => row.id}
              onRowClick={handleRowClick}
              resizeConfig={resizeConfig}
              selectionConfig={{
                rowSelection,
                setRowSelection,
              }}
              noData={
                <DataTableNoMatchingData
                  onClearFilters={
                    search || filters.length > 0
                      ? handleClearFilters
                      : undefined
                  }
                />
              }
              showSkeleton={isTableLoading}
              showLoadingOverlay={
                !isTableLoading && isPlaceholderData && isFetching
              }
            />
            <div className="py-4">
              <DataTablePagination
                page={page!}
                pageChange={setPage}
                size={pageSize}
                total={total}
              />
            </div>
          </div>
        </>
      )}
      <AddOptimizationDialog
        key={resetDialogKeyRef.current}
        open={openDialog}
        setOpen={setOpenDialog}
        projectId={activeProjectId}
      />
    </div>
  );
};

export default OptimizationsPage;
