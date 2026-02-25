import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { RotateCw } from "lucide-react";
import useLocalStorageState from "use-local-storage-state";
import { GroupingState, Row, RowSelectionState } from "@tanstack/react-table";
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
import Loader from "@/components/shared/Loader/Loader";
import useAppStore from "@/store/AppStore";
import { COLUMN_DATASET_ID, COLUMN_TYPE } from "@/types/shared";
import { Filter } from "@/types/filters";
import { Optimization } from "@/types/optimizations";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import AddOptimizationDialog from "@/components/pages/OptimizationsPage/AddOptimizationDialog/AddOptimizationDialog";
import OptimizationsActionsPanel from "@/components/pages/OptimizationsPage/OptimizationsActionsPanel/OptimizationsActionsPanel";
import DatasetSelectBox from "@/components/pages-shared/experiments/DatasetSelectBox/DatasetSelectBox";
import FiltersButton from "@/components/shared/FiltersButton/FiltersButton";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import { useExpandingConfig } from "@/components/pages-shared/experiments/useExpandingConfig";
import {
  getIsGroupRow,
  getRowId,
  renderCustomRow,
} from "@/components/shared/DataTable/utils";
import { GROUPING_COLUMN } from "@/constants/groups";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import ExplainerDescription from "@/components/shared/ExplainerDescription/ExplainerDescription";
import StudioTemplates from "@/components/pages-shared/optimizations/StudioTemplates";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import {
  DEFAULT_COLUMNS,
  useOptimizationsView,
} from "@/hooks/useOptimizationsView";
import { GroupedOptimization } from "@/hooks/useGroupedOptimizationsList";

const SELECTED_COLUMNS_KEY = "optimizations-selected-columns";
const COLUMNS_WIDTH_KEY = "optimizations-columns-width";
const COLUMNS_ORDER_KEY = "optimizations-columns-order";

export const GROUPING_CONFIG = {
  groupedColumnMode: false as const,
  grouping: [GROUPING_COLUMN] as GroupingState,
};

export const FILTER_COLUMNS = [
  {
    id: COLUMN_DATASET_ID,
    label: "Dataset",
    type: COLUMN_TYPE.string,
    disposable: true,
  },
];

export const DEFAULT_SELECTED_COLUMNS: string[] = [
  "created_at",
  "num_trials",
  "optimizer",
  "objective_name",
  "status",
];

const OptimizationsPage: React.FunctionComponent = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const navigate = useNavigate();
  const resetDialogKeyRef = useRef(0);
  const [openDialog, setOpenDialog] = useState<boolean>(false);
  const isOptimizationStudioEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.OPTIMIZATION_STUDIO_ENABLED,
  );

  const [search = "", setSearch] = useQueryParam("search", StringParam, {
    updateType: "replaceIn",
  });

  const [filters = [], setFilters] = useQueryParam("filters", JsonParam, {
    updateType: "replaceIn",
  });

  const [page = 1, setPage] = useQueryParam("page", NumberParam, {
    updateType: "replaceIn",
  });

  const [groupLimit, setGroupLimit] = useQueryParam<Record<string, number>>(
    "limits",
    { ...JsonParam, default: {} },
    {
      updateType: "replaceIn",
    },
  );

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
      defaultValue: [],
    },
  );

  const [columnsWidth, setColumnsWidth] = useLocalStorageState<
    Record<string, number>
  >(COLUMNS_WIDTH_KEY, {
    defaultValue: {},
  });

  const {
    optimizations,
    groupIds,
    total,
    selectedRows,
    isPending,
    isPlaceholderData,
    isFetching,
    refetch,
    columns,
    columnPinning,
    pageSize,
  } = useOptimizationsView({
    workspaceName,
    datasetId,
    search: search || "",
    page: page || 1,
    groupLimit,
    columnsOrder,
    selectedColumns,
    rowSelection,
  });

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      columnSizing: columnsWidth,
      onColumnResize: setColumnsWidth,
    }),
    [columnsWidth, setColumnsWidth],
  );

  const handleRowClick = useCallback(
    (row: Optimization | GroupedOptimization) => {
      navigate({
        to: "/$workspaceName/optimizations/$datasetId/compare",
        params: {
          datasetId: row.dataset_id,
          workspaceName,
        },
        search: {
          optimizations: [row.id],
        },
      });
    },
    [navigate, workspaceName],
  );

  const expandingConfig = useExpandingConfig({});

  const openGroupsRef = useRef<Record<string, boolean>>({});
  useEffect(() => {
    const updateForExpandedState: Record<string, boolean> = {};
    groupIds.forEach((groupId) => {
      const id = `${GROUPING_COLUMN}:${groupId}`;
      if (!openGroupsRef.current[id]) {
        openGroupsRef.current[id] = true;
        updateForExpandedState[id] = true;
      }
    });

    if (Object.keys(updateForExpandedState).length) {
      expandingConfig.setExpanded((state) => {
        if (state === true) return state;
        return {
          ...state,
          ...updateForExpandedState,
        };
      });
    }
  }, [expandingConfig, groupIds]);

  const handleNewOptimizationClick = useCallback(() => {
    setOpenDialog(true);
    resetDialogKeyRef.current = resetDialogKeyRef.current + 1;
  }, []);

  const renderCustomRowCallback = useCallback(
    (row: Row<GroupedOptimization>) => {
      return renderCustomRow(row, setGroupLimit);
    },
    [setGroupLimit],
  );

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
              placeholder="Search by name"
              className="w-[320px]"
              dimension="sm"
            ></SearchInput>
            <FiltersButton
              columns={FILTER_COLUMNS}
              config={filtersConfig as never}
              filters={filters}
              onChange={setFilters}
              layout="icon"
            />
          </div>
          <div className="flex items-center gap-2">
            <OptimizationsActionsPanel optimizations={selectedRows} />
            <Separator orientation="vertical" className="mx-2 h-4" />
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
              columns={DEFAULT_COLUMNS}
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
          renderCustomRow={renderCustomRowCallback}
          getIsCustomRow={getIsGroupRow}
          resizeConfig={resizeConfig}
          selectionConfig={{
            rowSelection,
            setRowSelection,
          }}
          expandingConfig={expandingConfig}
          groupingConfig={GROUPING_CONFIG}
          getRowId={getRowId}
          columnPinning={columnPinning}
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
