import { useCallback, useEffect, useMemo } from "react";
import { useNavigate } from "@tanstack/react-router";
import { keepPreviousData } from "@tanstack/react-query";
import { ColumnSort } from "@tanstack/react-table";
import useLocalStorageState from "use-local-storage-state";
import { JsonParam, StringParam, useQueryParam } from "use-query-params";
import isArray from "lodash/isArray";

import { COLUMN_FEEDBACK_SCORES_ID, ROW_HEIGHT } from "@/types/shared";
import { Experiment, EXPERIMENT_TYPE } from "@/types/datasets";
import { OPTIMIZATION_STATUS } from "@/types/optimizations";
import {
  IN_PROGRESS_OPTIMIZATION_STATUSES,
  OPTIMIZATION_ACTIVE_REFETCH_INTERVAL,
} from "@/lib/optimizations";
import useAppStore from "@/store/AppStore";
import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import useOptimizationById from "@/api/optimizations/useOptimizationById";
import useExperimentsList from "@/api/datasets/useExperimentsList";
import { useOptimizationScores } from "@/components/pages-shared/experiments/useOptimizationScores";

const REFETCH_INTERVAL = 30000;
const MAX_EXPERIMENTS_LOADED = 1000;

const SELECTED_COLUMNS_KEY = "optimization-experiments-selected-columns";
const COLUMNS_WIDTH_KEY = "optimization-experiments-columns-width";
const COLUMNS_ORDER_KEY = "optimization-experiments-columns-order";
const COLUMNS_SORT_KEY = "optimization-experiments-columns-sort";
const ROW_HEIGHT_KEY = "optimization-experiments-row-height";

const DEFAULT_SELECTED_COLUMNS: string[] = [
  "prompt",
  "objective_name",
  "created_at",
];

export const useCompareOptimizationsData = () => {
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const setBreadcrumbParam = useBreadcrumbsStore((state) => state.setParam);

  const [search = "", setSearch] = useQueryParam("search", StringParam, {
    updateType: "replaceIn",
  });

  const [optimizationsIds = []] = useQueryParam("optimizations", JsonParam, {
    updateType: "replaceIn",
  });

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
          status &&
          IN_PROGRESS_OPTIMIZATION_STATUSES.includes(
            status as OPTIMIZATION_STATUS,
          )
        ) {
          return OPTIMIZATION_ACTIVE_REFETCH_INTERVAL;
        }
        return REFETCH_INTERVAL;
      },
    },
  );

  const isActiveOptimization =
    !!optimization?.status &&
    IN_PROGRESS_OPTIMIZATION_STATUSES.includes(optimization.status);

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
        ? OPTIMIZATION_ACTIVE_REFETCH_INTERVAL
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

  return {
    // State
    workspaceName,
    optimizationId,
    optimization,
    experiments,
    rows,
    title,
    noDataText,
    scoreMap,
    bestExperiment,
    baselineExperiment,
    sortableBy,
    // Loading states
    isOptimizationPending,
    isExperimentsPending,
    // Search
    search,
    setSearch,
    // Column state
    sortedColumns,
    setSortedColumns,
    selectedColumns,
    setSelectedColumns,
    columnsOrder,
    setColumnsOrder,
    columnsWidth,
    setColumnsWidth,
    // Row height
    height,
    setHeight,
    // Handlers
    handleRowClick,
    handleRefresh,
  };
};
