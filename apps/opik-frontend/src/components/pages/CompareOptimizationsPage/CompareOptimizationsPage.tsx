import React, { useCallback, useEffect, useMemo } from "react";
import { useNavigate } from "@tanstack/react-router";
import { keepPreviousData } from "@tanstack/react-query";
import { ColumnPinningState, ColumnSort } from "@tanstack/react-table";
import useLocalStorageState from "use-local-storage-state";
import { JsonParam, StringParam, useQueryParam } from "use-query-params";
import { RotateCw } from "lucide-react";
import get from "lodash/get";
import isArray from "lodash/isArray";
import isUndefined from "lodash/isUndefined";
import isObject from "lodash/isObject";

import {
  COLUMN_CREATED_AT_ID,
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_ID_ID,
  COLUMN_NAME_ID,
  COLUMN_TYPE,
  ColumnData,
  ROW_HEIGHT,
} from "@/types/shared";
import {
  OPTIMIZATION_EXAMPLES_KEY,
  OPTIMIZATION_OPTIMIZER_KEY,
  OPTIMIZATION_PROMPT_KEY,
  STATUS_TO_VARIANT_MAP,
} from "@/constants/experiments";
import { Experiment, EXPERIMENT_TYPE } from "@/types/datasets";
import { formatDate } from "@/lib/date";
import { toString } from "@/lib/utils";
import { getFeedbackScoreValue } from "@/lib/feedback-scores";
import { convertColumnDataToColumn, mapColumnDataFields } from "@/lib/table";
import useAppStore from "@/store/AppStore";
import { Button } from "@/components/ui/button";
import { Tag } from "@/components/ui/tag";
import useOptimizationById from "@/api/optimizations/useOptimizationById";
import useExperimentsList from "@/api/datasets/useExperimentsList";
import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import PageBodyScrollContainer from "@/components/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import Loader from "@/components/shared/Loader/Loader";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import DataTableRowHeightSelector from "@/components/shared/DataTableRowHeightSelector/DataTableRowHeightSelector";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import PageBodyStickyTableWrapper from "@/components/layout/PageBodyStickyTableWrapper/PageBodyStickyTableWrapper";
import DataTableVirtualBody from "@/components/shared/DataTable/DataTableVirtualBody";
import DataTable from "@/components/shared/DataTable/DataTable";
import IdCell from "@/components/shared/DataTableCells/IdCell";
import ResourceCell from "@/components/shared/DataTableCells/ResourceCell";
import ObjectiveScoreCell from "@/components/pages/CompareOptimizationsPage/ObjectiveScoreCell";
import FeedbackScoreHeader from "@/components/shared/DataTableHeaders/FeedbackScoreHeader";
import BestPrompt from "@/components/pages/CompareOptimizationsPage/BestPrompt";
import OptimizationProgressChartContainer from "@/components/pages/CompareOptimizationsPage/OptimizationProgressChartContainer";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";

const REFETCH_INTERVAL = 30000;
const MAX_EXPERIMENTS_LOADED = 1000;

export const getRowId = (e: Experiment) => e.id;

const calculatePercentageChange = (
  baseValue: number,
  newValue: number,
): number | undefined => {
  if (baseValue === 0 && newValue === 0) return 0;
  if (baseValue === 0) return undefined;
  return ((newValue - baseValue) / Math.abs(baseValue)) * 100;
};

const SELECTED_COLUMNS_KEY = "optimization-experiments-selected-columns";
const COLUMNS_WIDTH_KEY = "optimization-experiments-columns-width";
const COLUMNS_ORDER_KEY = "optimization-experiments-columns-order";
const COLUMNS_SORT_KEY = "optimization-experiments-columns-sort";
const ROW_HEIGHT_KEY = "optimization-experiments-row-height";

export const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_NAME_ID],
  right: [],
};

export const DEFAULT_SELECTED_COLUMNS: string[] = [
  "optimizer",
  "prompt",
  "examples",
  "objective_name",
  COLUMN_CREATED_AT_ID,
];

const CompareOptimizationsPage: React.FC = () => {
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [search = "", setSearch] = useQueryParam("search", StringParam, {
    updateType: "replaceIn",
  });

  const [optimizationsIds = []] = useQueryParam("optimizations", JsonParam, {
    updateType: "replaceIn",
  });

  const setBreadcrumbParam = useBreadcrumbsStore((state) => state.setParam);

  const [sortedColumns, setSortedColumns] = useLocalStorageState<ColumnSort[]>(
    COLUMNS_SORT_KEY,
    {
      defaultValue: [],
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

  const [height, setHeight] = useLocalStorageState<ROW_HEIGHT>(ROW_HEIGHT_KEY, {
    defaultValue: ROW_HEIGHT.small,
  });

  const optimizationId = optimizationsIds?.[0];

  const {
    data: optimization,
    isPending: isOptimizationPending,
    refetch: refetchOptimization,
  } = useOptimizationById(
    {
      optimizationId,
    },
    {
      placeholderData: keepPreviousData,
      enabled: !!optimizationId,
      refetchInterval: REFETCH_INTERVAL,
    },
  );

  const {
    data,
    isPending: isExperimentsPending,
    refetch: refetchExperiments,
  } = useExperimentsList(
    {
      workspaceName,
      optimizationId: optimizationId,
      sorting: sortedColumns.map((column) => {
        if (column.id === "objective_name") {
          return {
            ...column,
            id: `${COLUMN_FEEDBACK_SCORES_ID}.${optimization?.objective_name}`,
          };
        }
        return column;
      }),
      types: [EXPERIMENT_TYPE.TRIAL, EXPERIMENT_TYPE.MINI_BATCH],
      page: 1,
      size: MAX_EXPERIMENTS_LOADED,
    },
    {
      placeholderData: keepPreviousData,
      refetchInterval: REFETCH_INTERVAL,
    },
  );

  const sortableBy: string[] = useMemo(
    () =>
      isArray(data?.sortable_by) ? [...data.sortable_by, "objective_name"] : [],
    [data?.sortable_by],
  );

  const title = optimization?.name || optimizationId;
  const noData = !search;
  const noDataText = noData ? "There are no trials yet" : "No search results";
  const experiments = useMemo(() => data?.content ?? [], [data?.content]);

  useEffect(() => {
    title &&
      setBreadcrumbParam("optimizationsCompare", "optimizationsCompare", title);
    return () =>
      setBreadcrumbParam("optimizationsCompare", "optimizationsCompare", "");
  }, [title, setBreadcrumbParam]);

  const rows = useMemo(
    () =>
      experiments.filter(({ name }) =>
        name.toLowerCase().includes(search!.toLowerCase()),
      ),
    [experiments, search],
  );

  const { scoreMap, bestExperiment } = useMemo(() => {
    const retVal: {
      scoreMap: Record<string, { score: number; percentage?: number }>;
      baseScore: number;
      bestExperiment?: Experiment;
    } = {
      scoreMap: {},
      baseScore: 0,
    };
    let maxScoreValue: number;

    const sortedRows = experiments
      .slice()
      .sort((e1, e2) => e1.created_at.localeCompare(e2.created_at));

    if (
      !optimization?.objective_name ||
      !experiments.length ||
      !isArray(sortedRows?.[0]?.feedback_scores)
    )
      return retVal;

    retVal.baseScore =
      getFeedbackScoreValue(
        sortedRows[0].feedback_scores,
        optimization.objective_name,
      ) ?? 0;

    // if baseScore is 0, then we cannot calculate the relative score
    if (retVal.baseScore === 0) return retVal;

    experiments.forEach((e) => {
      const score = getFeedbackScoreValue(
        e.feedback_scores ?? [],
        optimization.objective_name,
      );

      if (!isUndefined(score)) {
        if (isUndefined(maxScoreValue) || score > maxScoreValue) {
          maxScoreValue = score;
          retVal.bestExperiment = e;
        }

        retVal.scoreMap[e.id] = {
          score,
          percentage: calculatePercentageChange(retVal.baseScore, score),
        };
      }
    });

    return retVal;
  }, [experiments, optimization?.objective_name]);

  const columnsDef: ColumnData<Experiment>[] = useMemo(() => {
    if (!optimization?.objective_name) return [];

    return [
      {
        id: COLUMN_ID_ID,
        label: "ID",
        type: COLUMN_TYPE.string,
        cell: IdCell as never,
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
      },
      {
        id: "prompt",
        label: "Prompt",
        type: COLUMN_TYPE.string,
        size: 400,
        accessorFn: (row) => {
          const val = get(row.metadata ?? {}, OPTIMIZATION_PROMPT_KEY, "-");

          return isObject(val) ? JSON.stringify(val, null, 2) : toString(val);
        },
      },
      {
        id: "examples",
        label: "Examples",
        type: COLUMN_TYPE.string,
        size: 400,
        accessorFn: (row) => {
          const val = get(row.metadata ?? {}, OPTIMIZATION_EXAMPLES_KEY, "-");

          return isObject(val) ? JSON.stringify(val, null, 2) : toString(val);
        },
      },
      {
        id: `objective_name`,
        label: optimization.objective_name,
        type: COLUMN_TYPE.number,
        header: FeedbackScoreHeader as never,
        cell: ObjectiveScoreCell as never,
        customMeta: {
          scoreMap,
        },
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
    ];
  }, [optimization?.objective_name, scoreMap]);

  const columns = useMemo(() => {
    return [
      mapColumnDataFields<Experiment, Experiment>({
        id: COLUMN_NAME_ID,
        label: "Trial",
        type: COLUMN_TYPE.string,
        cell: ResourceCell as never,
        sortable: true,
        customMeta: {
          nameKey: "name",
          idKey: "dataset_id",
          resource: RESOURCE_TYPE.trial,
          getParams: () => ({
            optimizationId,
          }),
          getSearch: (data: Experiment) => ({
            trials: [data.id],
          }),
        },
      }),
      ...convertColumnDataToColumn<Experiment, Experiment>(columnsDef, {
        columnsOrder,
        selectedColumns,
        sortableColumns: sortableBy,
      }),
    ];
  }, [columnsDef, columnsOrder, selectedColumns, sortableBy, optimizationId]);

  const sortConfig = useMemo(
    () => ({
      enabled: true,
      sorting: sortedColumns,
      setSorting: setSortedColumns,
    }),
    [setSortedColumns, sortedColumns],
  );

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      columnSizing: columnsWidth,
      onColumnResize: setColumnsWidth,
    }),
    [columnsWidth, setColumnsWidth],
  );

  const handleRowClick = useCallback(
    (row: Experiment) => {
      navigate({
        to: "/$workspaceName/optimizations/$datasetId/$optimizationId/compare",
        params: {
          datasetId: row.dataset_id,
          optimizationId,
          workspaceName,
        },
        search: {
          trials: [row.id],
        },
      });
    },
    [navigate, workspaceName, optimizationId],
  );

  if (isOptimizationPending || isExperimentsPending) {
    return <Loader />;
  }

  return (
    <PageBodyScrollContainer>
      <PageBodyStickyContainer
        className="pb-4 pt-6"
        direction="horizontal"
        limitWidth
      >
        <div className="mb-4 flex min-h-8 flex-nowrap items-center gap-2">
          <h1 className="comet-title-l truncate break-words">{title}</h1>
          {optimization?.status && (
            <Tag
              variant={STATUS_TO_VARIANT_MAP[optimization.status]}
              size="md"
              className="capitalize"
            >
              {optimization.status}
            </Tag>
          )}
        </div>
      </PageBodyStickyContainer>
      <PageBodyStickyContainer
        className="-mt-4 flex flex-wrap items-center justify-between gap-x-8 gap-y-2 pb-6 pt-4"
        direction="bidirectional"
        limitWidth
      >
        <div className="flex items-center gap-2">
          <SearchInput
            searchText={search!}
            setSearchText={setSearch}
            placeholder="Search by name"
            className="w-[320px]"
            dimension="sm"
          ></SearchInput>
        </div>
        <div className="flex items-center gap-2">
          <TooltipWrapper content={`Refresh trials list`}>
            <Button
              variant="outline"
              size="icon-sm"
              className="shrink-0"
              onClick={() => {
                refetchOptimization();
                refetchExperiments();
              }}
            >
              <RotateCw />
            </Button>
          </TooltipWrapper>
          <DataTableRowHeightSelector
            type={height as ROW_HEIGHT}
            setType={setHeight}
          />
          <ColumnsButton
            columns={columnsDef}
            selectedColumns={selectedColumns}
            onSelectionChange={setSelectedColumns}
            order={columnsOrder}
            onOrderChange={setColumnsOrder}
          ></ColumnsButton>
        </div>
      </PageBodyStickyContainer>
      <PageBodyStickyContainer
        className="z-[9] flex flex-row gap-x-4 overflow-x-auto pb-6"
        direction="horizontal"
        limitWidth
      >
        <OptimizationProgressChartContainer
          experiments={experiments}
          bestEntityId={bestExperiment?.id}
          objectiveName={optimization?.objective_name}
        />
        {bestExperiment && optimization ? (
          <BestPrompt
            experiment={bestExperiment}
            optimization={optimization}
            scoreMap={scoreMap}
          ></BestPrompt>
        ) : null}
      </PageBodyStickyContainer>
      <DataTable
        columns={columns}
        data={rows}
        onRowClick={handleRowClick}
        sortConfig={sortConfig}
        resizeConfig={resizeConfig}
        getRowId={getRowId}
        rowHeight={height}
        columnPinning={DEFAULT_COLUMN_PINNING}
        noData={<DataTableNoData title={noDataText} />}
        TableWrapper={PageBodyStickyTableWrapper}
        TableBody={DataTableVirtualBody}
        stickyHeader
      />
      <PageBodyStickyContainer
        className="h-4"
        direction="horizontal"
        limitWidth
      ></PageBodyStickyContainer>
    </PageBodyScrollContainer>
  );
};

export default CompareOptimizationsPage;
