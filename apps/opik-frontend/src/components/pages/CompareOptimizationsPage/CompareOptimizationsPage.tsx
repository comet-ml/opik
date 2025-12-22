import React, { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate } from "@tanstack/react-router";
import { keepPreviousData } from "@tanstack/react-query";
import { ColumnSort } from "@tanstack/react-table";
import useLocalStorageState from "use-local-storage-state";
import { JsonParam, StringParam, useQueryParam } from "use-query-params";
import get from "lodash/get";
import isArray from "lodash/isArray";
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
import { getOptimizerLabel } from "@/lib/optimizations";
import { Experiment, EXPERIMENT_TYPE } from "@/types/datasets";
import { OPTIMIZATION_STATUS } from "@/types/optimizations";
import { formatDate } from "@/lib/date";
import { toString } from "@/lib/utils";
import { convertColumnDataToColumn, mapColumnDataFields } from "@/lib/table";
import useAppStore from "@/store/AppStore";
import { useOptimizationScores } from "@/components/pages-shared/experiments/useOptimizationScores";
import { Tag } from "@/components/ui/tag";
import useOptimizationById from "@/api/optimizations/useOptimizationById";
import useExperimentsList from "@/api/datasets/useExperimentsList";
import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import PageBodyScrollContainer from "@/components/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import Loader from "@/components/shared/Loader/Loader";
import IdCell from "@/components/shared/DataTableCells/IdCell";
import ResourceCell from "@/components/shared/DataTableCells/ResourceCell";
import ObjectiveScoreCell from "@/components/pages/CompareOptimizationsPage/ObjectiveScoreCell";
import FeedbackScoreHeader from "@/components/shared/DataTableHeaders/FeedbackScoreHeader";
import { BestPrompt } from "@/components/pages-shared/experiments/BestPromptCard";
import OptimizationProgressChartContainer from "@/components/pages-shared/experiments/OptimizationProgressChart";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";
import { OPTIMIZATION_VIEW_TYPE } from "@/components/pages/CompareOptimizationsPage/OptimizationViewSelector";
import OptimizationLogs from "@/components/pages-shared/optimizations/OptimizationLogs/OptimizationLogs";
import CompareOptimizationsToolbar from "./CompareOptimizationsToolbar";
import CompareOptimizationsTrialsControls from "./CompareOptimizationsTrialsControls";
import CompareOptimizationsTrialsTable from "./CompareOptimizationsTrialsTable";
import CompareOptimizationsConfiguration from "./CompareOptimizationsConfiguration";
import BestPromptPlaceholder from "./BestPromptPlaceholder";

const REFETCH_INTERVAL = 30000;
const ACTIVE_REFETCH_INTERVAL = 3000;
const MAX_EXPERIMENTS_LOADED = 1000;

const SELECTED_COLUMNS_KEY = "optimization-experiments-selected-columns";
const COLUMNS_WIDTH_KEY = "optimization-experiments-columns-width";
const COLUMNS_ORDER_KEY = "optimization-experiments-columns-order";
const COLUMNS_SORT_KEY = "optimization-experiments-columns-sort";
const ROW_HEIGHT_KEY = "optimization-experiments-row-height";

const DEFAULT_SELECTED_COLUMNS: string[] = [
  "prompt",
  "objective_name",
  COLUMN_CREATED_AT_ID,
];

const CompareOptimizationsPage: React.FC = () => {
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [view, setView] = useState<OPTIMIZATION_VIEW_TYPE>(
    OPTIMIZATION_VIEW_TYPE.LOGS,
  );
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
      refetchInterval: (query) => {
        if (!optimizationId) return false;
        const status = query.state.data?.status;
        if (
          status === OPTIMIZATION_STATUS.RUNNING ||
          status === OPTIMIZATION_STATUS.INITIALIZED
        ) {
          return ACTIVE_REFETCH_INTERVAL;
        }
        return REFETCH_INTERVAL;
      },
    },
  );

  const isActiveOptimization =
    optimization?.status === OPTIMIZATION_STATUS.RUNNING ||
    optimization?.status === OPTIMIZATION_STATUS.INITIALIZED;

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
      refetchInterval: isActiveOptimization
        ? ACTIVE_REFETCH_INTERVAL
        : REFETCH_INTERVAL,
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

  const { scoreMap, bestExperiment } = useOptimizationScores(
    experiments,
    optimization?.objective_name,
  );

  const baselineExperiment = useMemo(() => {
    if (!experiments.length) return undefined;
    const sortedRows = experiments
      .slice()
      .sort((e1, e2) => e1.created_at.localeCompare(e2.created_at));
    return sortedRows[0];
  }, [experiments]);

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
          const metadataVal = get(
            row.metadata ?? {},
            OPTIMIZATION_OPTIMIZER_KEY,
          );
          if (metadataVal) {
            return isObject(metadataVal)
              ? JSON.stringify(metadataVal, null, 2)
              : toString(metadataVal);
          }
          const studioVal = optimization?.studio_config?.optimizer?.type;
          return studioVal ? getOptimizerLabel(studioVal) : "-";
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

  const handleRefresh = useCallback(() => {
    refetchOptimization();
    refetchExperiments();
  }, [refetchOptimization, refetchExperiments]);

  if (isOptimizationPending || isExperimentsPending) {
    return <Loader />;
  }

  const isStudioOptimization = Boolean(optimization?.studio_config);
  const showTrialsView =
    !isStudioOptimization || view === OPTIMIZATION_VIEW_TYPE.TRIALS;
  const showLogsView =
    isStudioOptimization && view === OPTIMIZATION_VIEW_TYPE.LOGS;
  const showConfigurationView =
    isStudioOptimization && view === OPTIMIZATION_VIEW_TYPE.CONFIGURATION;

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
        className="pb-6"
        direction="horizontal"
        limitWidth
      >
        <OptimizationProgressChartContainer
          experiments={experiments}
          bestEntityId={bestExperiment?.id}
          objectiveName={optimization?.objective_name}
        />
      </PageBodyStickyContainer>

      <PageBodyStickyContainer
        className="-mt-4 flex flex-wrap items-center justify-between gap-x-8 gap-y-2 pb-6 pt-4"
        direction="bidirectional"
        limitWidth
      >
        <div className="flex items-center gap-2">
          <CompareOptimizationsToolbar
            isStudioOptimization={isStudioOptimization}
            view={view}
            onViewChange={setView}
            search={search!}
            onSearchChange={setSearch}
          />
        </div>
        {showTrialsView && (
          <CompareOptimizationsTrialsControls
            onRefresh={handleRefresh}
            rowHeight={height}
            onRowHeightChange={setHeight}
            columnsDef={columnsDef}
            selectedColumns={selectedColumns}
            onSelectedColumnsChange={setSelectedColumns}
            columnsOrder={columnsOrder}
            onColumnsOrderChange={setColumnsOrder}
          />
        )}
      </PageBodyStickyContainer>

      <PageBodyStickyContainer
        className="flex flex-row gap-x-4 pb-6"
        direction="horizontal"
        limitWidth
      >
        <div className="flex min-w-0 flex-1">
          {showLogsView && <OptimizationLogs optimization={optimization!} />}
          {showTrialsView && (
            <CompareOptimizationsTrialsTable
              columns={columns}
              rows={rows}
              onRowClick={handleRowClick}
              rowHeight={height}
              noDataText={noDataText}
              sortedColumns={sortedColumns}
              onSortChange={setSortedColumns}
              columnsWidth={columnsWidth}
              onColumnsWidthChange={setColumnsWidth}
            />
          )}
          {showConfigurationView && optimization?.studio_config && (
            <CompareOptimizationsConfiguration
              studioConfig={optimization.studio_config}
            />
          )}
        </div>
        <div className="w-2/5 shrink-0">
          {bestExperiment && optimization ? (
            <BestPrompt
              experiment={bestExperiment}
              optimization={optimization}
              scoreMap={scoreMap}
              baselineExperiment={baselineExperiment}
            />
          ) : (
            optimization?.studio_config && (
              <BestPromptPlaceholder
                objectiveName={optimization.objective_name}
                studioConfig={optimization.studio_config}
              />
            )
          )}
        </div>
      </PageBodyStickyContainer>

      <PageBodyStickyContainer
        className="h-4"
        direction="horizontal"
        limitWidth
      />
    </PageBodyScrollContainer>
  );
};

export default CompareOptimizationsPage;
