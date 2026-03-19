import React, { useCallback, useMemo, useRef, useState } from "react";
import { RotateCw } from "lucide-react";
import useLocalStorageState from "use-local-storage-state";
import { RowSelectionState } from "@tanstack/react-table";
import { useNavigate } from "@tanstack/react-router";
import {
  JsonParam,
  NumberParam,
  StringParam,
  useQueryParam,
} from "use-query-params";
import DataTable from "@/components/shared/DataTable/DataTable";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import DatasetNameCell from "@/components/pages/OptimizationsPage/DatasetNameCell";
import OptimizationStatusCell from "@/components/pages/OptimizationsPage/OptimizationStatusCell";
import {
  OptimizationPassRateCell,
  OptimizationAccuracyCell,
  OptimizationLatencyCell,
  OptimizationCostCell,
  OptimizationTotalCostCell,
} from "@/components/pages/OptimizationsPage/OptimizationMetricCells";
import Loader from "@/components/shared/Loader/Loader";
import useAppStore from "@/store/AppStore";
import TimeCell from "@/components/shared/DataTableCells/TimeCell";
import { COLUMN_DATASET_ID, COLUMN_TYPE, ColumnData } from "@/types/shared";
import { Filter } from "@/types/filters";
import { Optimization } from "@/types/optimizations";
import { getFeedbackScore } from "@/lib/feedback-scores";
import { convertColumnDataToColumn } from "@/lib/table";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import AddOptimizationDialog from "@/components/pages/OptimizationsPage/AddOptimizationDialog/AddOptimizationDialog";
import OptimizationsActionsPanel from "@/components/pages/OptimizationsPage/OptimizationsActionsPanel/OptimizationsActionsPanel";
import DatasetSelectBox from "@/components/pages-shared/experiments/DatasetSelectBox/DatasetSelectBox";
import FiltersButton from "@/components/shared/FiltersButton/FiltersButton";
import OptimizationRowActionsCell from "@/components/pages/OptimizationsPage/OptimizationRowActionsCell";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import {
  generateActionsColumDef,
  generateSelectColumDef,
} from "@/components/shared/DataTable/utils";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import ExplainerDescription from "@/components/shared/ExplainerDescription/ExplainerDescription";
import StudioTemplates from "@/components/pages-shared/optimizations/StudioTemplates";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { useOptimizationsView } from "@/hooks/useOptimizationsView";
import { usePermissions } from "@/contexts/PermissionsContext";

const SELECTED_COLUMNS_KEY = "optimizations-selected-columns-v1";
const COLUMNS_WIDTH_KEY = "optimizations-columns-width-v1";
const COLUMNS_ORDER_KEY = "optimizations-columns-order-v1";

export const DEFAULT_COLUMNS: ColumnData<Optimization>[] = [
  {
    id: "dataset_name",
    label: "Dataset name",
    type: COLUMN_TYPE.string,
    cell: DatasetNameCell as never,
    size: 200,
  },
  {
    id: "created_at",
    label: "Start time",
    type: COLUMN_TYPE.time,
    cell: TimeCell as never,
    size: 140,
  },
  {
    id: "status",
    label: "Status",
    type: COLUMN_TYPE.string,
    cell: OptimizationStatusCell as never,
    size: 120,
  },
  {
    id: "pass_rate",
    label: "Pass rate",
    type: COLUMN_TYPE.numberDictionary,
    size: 200,
    accessorFn: (row) => row.best_objective_score,
    cell: OptimizationPassRateCell as never,
  },
  {
    id: "accuracy",
    label: "Accuracy",
    type: COLUMN_TYPE.numberDictionary,
    size: 200,
    accessorFn: (row) =>
      getFeedbackScore(row.feedback_scores ?? [], row.objective_name),
    cell: OptimizationAccuracyCell as never,
  },
  {
    id: "latency",
    label: "Latency",
    type: COLUMN_TYPE.duration,
    size: 180,
    accessorFn: (row) => row.best_duration,
    cell: OptimizationLatencyCell as never,
  },
  {
    id: "cost",
    label: "Cost",
    type: COLUMN_TYPE.cost,
    size: 180,
    accessorFn: (row) => row.best_cost,
    cell: OptimizationCostCell as never,
  },
  {
    id: "opt_cost",
    label: "Opt. cost",
    type: COLUMN_TYPE.cost,
    size: 120,
    accessorFn: (row) => row.total_optimization_cost,
    cell: OptimizationTotalCostCell as never,
  },
];

export const FILTER_COLUMNS = [
  {
    id: COLUMN_DATASET_ID,
    label: "Evaluation suite",
    type: COLUMN_TYPE.string,
    disposable: true,
  },
];

export const DEFAULT_SELECTED_COLUMNS: string[] = [
  "dataset_name",
  "created_at",
  "status",
  "pass_rate",
  "accuracy",
  "latency",
  "cost",
  "opt_cost",
  "deploy",
];

const DEFAULT_COLUMNS_ORDER: string[] = [
  "dataset_name",
  "created_at",
  "status",
  "pass_rate",
  "accuracy",
  "latency",
  "cost",
  "opt_cost",
  "deploy",
];

const selectColumn = generateSelectColumDef();

const actionsColumn = generateActionsColumDef({
  cell: OptimizationRowActionsCell,
});

const OptimizationsPage: React.FunctionComponent = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const navigate = useNavigate();
  const resetDialogKeyRef = useRef(0);
  const [openDialog, setOpenDialog] = useState<boolean>(false);
  const isOptimizationStudioEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.OPTIMIZATION_STUDIO_ENABLED,
  );

  const {
    permissions: { canViewDatasets, canDeleteOptimizationRuns },
  } = usePermissions();

  const [search = "", setSearch] = useQueryParam("search", StringParam, {
    updateType: "replaceIn",
  });

  const [filters = [], setFilters] = useQueryParam("filters", JsonParam, {
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

  const filtersConfig = useMemo(
    () => ({
      rowsMap: {
        [COLUMN_DATASET_ID]: {
          keyComponent: DatasetSelectBox,
          keyComponentProps: {
            className: "w-full min-w-72",
          },
          defaultOperator: "=",
          operators: [{ label: "=", value: "=" }],
        },
      },
    }),
    [],
  );

  const noData = !search && filters.length === 0;
  const noDataText = noData
    ? "There are no optimizations yet\n" +
      "Optimizations help improve your LLM application's performance, accuracy, and overall user experience"
    : "No search results";

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
    datasetId,
    search: search || "",
    page: page || 1,
    rowSelection,
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
        to: "/$workspaceName/optimizations/$optimizationId",
        params: {
          optimizationId: row.id,
          workspaceName,
        },
      });
    },
    [navigate, workspaceName],
  );

  const handleNewOptimizationClick = useCallback(() => {
    setOpenDialog(true);
    resetDialogKeyRef.current = resetDialogKeyRef.current + 1;
  }, []);

  if (isPending) {
    return <Loader />;
  }

  return (
    <div className="pt-6">
      <div className="mb-1 flex items-center justify-between">
        <h1 className="comet-title-l truncate break-words">
          Optimization Studio
        </h1>
      </div>
      <ExplainerDescription
        {...EXPLAINERS_MAP[EXPLAINER_ID.whats_an_optimization_run]}
      />
      {isOptimizationStudioEnabled && <StudioTemplates />}
      <div className="pt-6">
        <h2 className="comet-title-s sticky top-0 z-10 truncate break-words bg-soft-background pb-3 pt-2">
          Optimization runs
        </h2>
        <div className="mb-4 flex flex-wrap items-center justify-between gap-x-8 gap-y-2">
          <div className="flex items-center gap-2">
            <SearchInput
              searchText={search!}
              setSearchText={setSearch}
              placeholder="Search by dataset name"
              className="w-[320px]"
              dimension="sm"
            ></SearchInput>
            {canViewDatasets && (
              <FiltersButton
                columns={FILTER_COLUMNS}
                config={filtersConfig as never}
                filters={filters}
                onChange={setFilters}
                layout="icon"
              />
            )}
          </div>
          <div className="flex items-center gap-2">
            {canDeleteOptimizationRuns && (
              <>
                <OptimizationsActionsPanel optimizations={selectedRows} />
                <Separator orientation="vertical" className="mx-2 h-4" />
              </>
            )}
            <TooltipWrapper content="Refresh optimizations list">
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
              columns={visibleColumns}
              selectedColumns={selectedColumns}
              onSelectionChange={setSelectedColumns}
              order={columnsOrder}
              onOrderChange={setColumnsOrder}
            ></ColumnsButton>
          </div>
        </div>
        <DataTable
          columns={columns as never}
          data={optimizations as never}
          onRowClick={handleRowClick}
          resizeConfig={resizeConfig}
          selectionConfig={{
            rowSelection,
            setRowSelection,
          }}
          noData={
            <DataTableNoData title={noDataText}>
              {noData && (
                <Button variant="link" onClick={handleNewOptimizationClick}>
                  Create new optimization
                </Button>
              )}
            </DataTableNoData>
          }
          showLoadingOverlay={isPlaceholderData && isFetching}
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
      <AddOptimizationDialog
        key={resetDialogKeyRef.current}
        open={openDialog}
        setOpen={setOpenDialog}
      />
    </div>
  );
};

export default OptimizationsPage;
