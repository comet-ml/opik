import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { Info, RotateCw } from "lucide-react";
import useLocalStorageState from "use-local-storage-state";
import {
  ColumnPinningState,
  GroupingState,
  Row,
  RowSelectionState,
} from "@tanstack/react-table";
import { useNavigate } from "@tanstack/react-router";
import {
  JsonParam,
  NumberParam,
  StringParam,
  useQueryParam,
} from "use-query-params";
import get from "lodash/get";
import isObject from "lodash/isObject";

import DataTable from "@/components/shared/DataTable/DataTable";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import IdCell from "@/components/shared/DataTableCells/IdCell";
import ResourceCell from "@/components/shared/DataTableCells/ResourceCell";
import FeedbackScoreTagCell from "@/components/shared/DataTableCells/FeedbackScoreTagCell";
import OptimizationStatusCell from "@/components/pages/OptimizationsPage/OptimizationStatusCell";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";
import Loader from "@/components/shared/Loader/Loader";
import useAppStore from "@/store/AppStore";
import { toString } from "@/lib/utils";
import { formatDate } from "@/lib/date";
import { getFeedbackScore } from "@/lib/feedback-scores";
import {
  COLUMN_DATASET_ID,
  COLUMN_ID_ID,
  COLUMN_NAME_ID,
  COLUMN_TYPE,
  ColumnData,
} from "@/types/shared";
import { Filter } from "@/types/filters";
import { Optimization } from "@/types/optimizations";
import { convertColumnDataToColumn } from "@/lib/table";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import AddOptimizationDialog from "@/components/pages/OptimizationsPage/AddOptimizationDialog/AddOptimizationDialog";
import OptimizationsActionsPanel from "@/components/pages/OptimizationsPage/OptimizationsActionsPanel/OptimizationsActionsPanel";
import DatasetSelectBox from "@/components/pages-shared/experiments/DatasetSelectBox/DatasetSelectBox";
import FiltersButton from "@/components/shared/FiltersButton/FiltersButton";
import OptimizationRowActionsCell from "@/components/pages/OptimizationsPage/OptimizationRowActionsCell";
import FeedbackScoresChartsWrapper from "@/components/pages-shared/experiments/FeedbackScoresChartsWrapper/FeedbackScoresChartsWrapper";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import useGroupedOptimizationsList, {
  GroupedOptimization,
} from "@/hooks/useGroupedOptimizationsList";
import { useExpandingConfig } from "@/components/pages-shared/experiments/useExpandingConfig";
import {
  generateActionsColumDef,
  generateGroupedRowCellDef,
  generateDataRowCellDef,
  getIsGroupRow,
  getRowId,
  getSharedShiftCheckboxClickHandler,
  renderCustomRow,
} from "@/components/shared/DataTable/utils";
import { checkIsGroupRowType } from "@/lib/groups";
import {
  DEFAULT_GROUPS_PER_PAGE,
  DELETED_DATASET_ID,
  GROUPING_COLUMN,
} from "@/constants/groups";
import { OPTIMIZATION_OPTIMIZER_KEY } from "@/constants/experiments";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import ExplainerDescription from "@/components/shared/ExplainerDescription/ExplainerDescription";
import { ChartData } from "@/components/pages-shared/experiments/FeedbackScoresChartsWrapper/FeedbackScoresChartContent";

const SELECTED_COLUMNS_KEY = "optimizations-selected-columns";
const COLUMNS_WIDTH_KEY = "optimizations-columns-width";
const COLUMNS_ORDER_KEY = "optimizations-columns-order";

export const GROUPING_CONFIG = {
  groupedColumnMode: false as const,
  grouping: [GROUPING_COLUMN] as GroupingState,
};

export const DEFAULT_COLUMNS: ColumnData<GroupedOptimization>[] = [
  {
    id: COLUMN_ID_ID,
    label: "ID",
    type: COLUMN_TYPE.string,
    cell: IdCell as never,
  },
  {
    id: "created_at",
    label: "Created",
    type: COLUMN_TYPE.time,
    accessorFn: (row) => formatDate(row.created_at),
  },
  {
    id: "created_by",
    label: "Created by",
    type: COLUMN_TYPE.string,
  },
  {
    id: "num_trials",
    label: "Trial count",
    type: COLUMN_TYPE.number,
  },
  {
    id: "optimizer",
    label: "Optimizer",
    type: COLUMN_TYPE.string,
    size: 200,
    accessorFn: (row) => {
      const val = get(row.metadata ?? {}, OPTIMIZATION_OPTIMIZER_KEY, "-");

      return isObject(val) ? JSON.stringify(val, null, 2) : toString(val);
    },
    explainer: EXPLAINERS_MAP[EXPLAINER_ID.whats_the_optimizer],
  },
  {
    id: "objective_name",
    label: "Best score",
    type: COLUMN_TYPE.numberDictionary,
    accessorFn: (row) =>
      getFeedbackScore(row.feedback_scores ?? [], row.objective_name),
    cell: FeedbackScoreTagCell as never,
    explainer: EXPLAINERS_MAP[EXPLAINER_ID.whats_the_best_score],
  },
  {
    id: "status",
    label: "Status",
    type: COLUMN_TYPE.string,
    cell: OptimizationStatusCell as never,
  },
];

export const FILTER_COLUMNS: ColumnData<GroupedOptimization>[] = [
  {
    id: COLUMN_DATASET_ID,
    label: "Dataset",
    type: COLUMN_TYPE.string,
    disposable: true,
  },
];

export const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_NAME_ID, GROUPING_COLUMN],
  right: [],
};

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

  const { checkboxClickHandler } = useMemo(() => {
    return {
      checkboxClickHandler: getSharedShiftCheckboxClickHandler(),
    };
  }, []);

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

  const { data, isPending, refetch } = useGroupedOptimizationsList({
    workspaceName,
    groupLimit,
    datasetId: datasetId!,
    search: search!,
    page: page!,
    size: DEFAULT_GROUPS_PER_PAGE,
    polling: true,
  });

  const optimizations = useMemo(() => data?.content ?? [], [data?.content]);

  const groupIds = useMemo(() => data?.groupIds ?? [], [data?.groupIds]);
  const total = data?.total ?? 0;
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

  const selectedRows: Array<GroupedOptimization> = useMemo(() => {
    return optimizations.filter(
      (row) => rowSelection[row.id] && !checkIsGroupRowType(row.id),
    );
  }, [rowSelection, optimizations]);

  const columns = useMemo(() => {
    return [
      generateDataRowCellDef<GroupedOptimization>(
        {
          id: COLUMN_NAME_ID,
          label: "Name",
          type: COLUMN_TYPE.string,
          cell: ResourceCell as never,
          customMeta: {
            nameKey: "name",
            idKey: "dataset_id",
            resource: RESOURCE_TYPE.optimization,
            getSearch: (data: Optimization) => ({
              optimizations: [data.id],
            }),
          },
          headerCheckbox: true,
          size: 200,
        },
        checkboxClickHandler,
      ),
      generateGroupedRowCellDef<GroupedOptimization, unknown>(
        {
          id: GROUPING_COLUMN,
          label: "Dataset",
          type: COLUMN_TYPE.string,
          cell: ResourceCell as never,
          customMeta: {
            nameKey: "dataset_name",
            idKey: "dataset_id",
            resource: RESOURCE_TYPE.dataset,
          },
        },
        checkboxClickHandler,
      ),
      ...convertColumnDataToColumn<GroupedOptimization, GroupedOptimization>(
        DEFAULT_COLUMNS,
        {
          columnsOrder,
          selectedColumns,
        },
      ),
      generateActionsColumDef({
        cell: OptimizationRowActionsCell,
      }),
    ];
  }, [checkboxClickHandler, columnsOrder, selectedColumns]);

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      columnSizing: columnsWidth,
      onColumnResize: setColumnsWidth,
    }),
    [columnsWidth, setColumnsWidth],
  );

  const handleRowClick = useCallback(
    (row: GroupedOptimization) => {
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

  const chartsData = useMemo(() => {
    const groupsMap: Record<string, ChartData> = {};
    const indexMap: Record<string, number> = {};
    let index = 0;

    optimizations.forEach((optimization) => {
      const key = optimization[GROUPING_COLUMN];
      if (key !== DELETED_DATASET_ID) {
        if (!groupsMap[key]) {
          indexMap[key] = index;
          groupsMap[key] = {
            id: key,
            name: optimization.dataset.name,
            data: [],
            lines: [],
          };
          index += 1;
        }

        // Only include the main objective score in the chart
        const objectiveName = optimization.objective_name;
        const objectiveScore = optimization.feedback_scores?.find(
          (score) => score.name === objectiveName,
        );

        groupsMap[key].data.unshift({
          entityId: optimization.id,
          entityName: optimization.name,
          createdDate: formatDate(optimization.created_at),
          scores: objectiveScore
            ? { [objectiveScore.name]: objectiveScore.value }
            : {},
        });

        // Only add the objective name to lines
        if (objectiveName && !groupsMap[key].lines.includes(objectiveName)) {
          groupsMap[key].lines.push(objectiveName);
        }
      }
    });

    return Object.values(groupsMap).sort(
      (g1, g2) => indexMap[g1.id] - indexMap[g2.id],
    );
  }, [optimizations]);

  if (isPending) {
    return <Loader />;
  }

  return (
    <div className="pt-6">
      <div className="mb-1 flex items-center justify-between">
        <h1 className="comet-title-l truncate break-words">
          Optimization runs
        </h1>
      </div>
      <ExplainerDescription
        className="mb-4"
        {...EXPLAINERS_MAP[EXPLAINER_ID.whats_an_optimization_run]}
      />
      <div className="mb-6 flex flex-wrap items-center justify-between gap-x-8 gap-y-2">
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
          <Button
            variant="outline"
            size="sm"
            onClick={handleNewOptimizationClick}
          >
            <Info className="mr-1.5 size-3.5" />
            Create new optimization
          </Button>
        </div>
      </div>
      {Boolean(optimizations.length) && (
        <FeedbackScoresChartsWrapper chartsData={chartsData} />
      )}
      <DataTable
        columns={columns}
        data={optimizations}
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
        columnPinning={DEFAULT_COLUMN_PINNING}
        noData={
          <DataTableNoData title={noDataText}>
            {noData && (
              <Button variant="link" onClick={handleNewOptimizationClick}>
                Create new optimization
              </Button>
            )}
          </DataTableNoData>
        }
      />
      <div className="py-4">
        <DataTablePagination
          page={page!}
          pageChange={setPage}
          size={DEFAULT_GROUPS_PER_PAGE}
          total={total}
        ></DataTablePagination>
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
