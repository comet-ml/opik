import { useCallback, useEffect, useMemo } from "react";
import { useNavigate } from "@tanstack/react-router";
import { keepPreviousData } from "@tanstack/react-query";
import { ColumnSort } from "@tanstack/react-table";
import useLocalStorageState from "use-local-storage-state";
import { JsonParam, StringParam, useQueryParam } from "use-query-params";
import isArray from "lodash/isArray";

import {
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_ID_ID,
  COLUMN_NAME_ID,
  ROW_HEIGHT,
} from "@/types/shared";
import { Experiment, EXPERIMENT_TYPE } from "@/types/datasets";
import { OPTIMIZATION_ACTIVE_REFETCH_INTERVAL } from "@/lib/optimizations";
import { migrateSelectedColumns } from "@/lib/table";
import useAppStore from "@/store/AppStore";
import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import useOptimizationById from "@/api/optimizations/useOptimizationById";
import useExperimentsList from "@/api/datasets/useExperimentsList";
import { useOptimizationScores } from "@/components/pages-shared/experiments/useOptimizationScores";

const MAX_EXPERIMENTS_LOADED = 1000;

const SELECTED_COLUMNS_KEY = "optimization-experiments-selected-columns";
const SELECTED_COLUMNS_KEY_V2 = `${SELECTED_COLUMNS_KEY}-v2`;
const COLUMNS_WIDTH_KEY = "optimization-experiments-columns-width";
const COLUMNS_ORDER_KEY = "optimization-experiments-columns-order";
const COLUMNS_SORT_KEY = "optimization-experiments-columns-sort-v2";
const ROW_HEIGHT_KEY = "optimization-experiments-row-height";

const DEFAULT_SELECTED_COLUMNS: string[] = [
  COLUMN_NAME_ID,
  COLUMN_ID_ID,
  "prompt",
  "objective_name",
  "created_at",
];

const DEFAULT_SORTING: ColumnSort[] = [{ id: COLUMN_ID_ID, desc: false }];

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
      defaultValue: DEFAULT_SORTING,
    },
  );

  const [selectedColumns, setSelectedColumns] = useLocalStorageState<string[]>(
    SELECTED_COLUMNS_KEY_V2,
    {
      defaultValue: migrateSelectedColumns(
        SELECTED_COLUMNS_KEY,
        DEFAULT_SELECTED_COLUMNS,
        [COLUMN_NAME_ID, COLUMN_ID_ID],
      ),
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
    { optimizationId },
    {
      placeholderData: keepPreviousData,
      enabled: !!optimizationId,
      refetchInterval: OPTIMIZATION_ACTIVE_REFETCH_INTERVAL,
    },
  );

  const {
    data,
    isPending: isExperimentsPending,
    isPlaceholderData: isExperimentsPlaceholderData,
    isFetching: isExperimentsFetching,
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
      refetchInterval: OPTIMIZATION_ACTIVE_REFETCH_INTERVAL,
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
    isExperimentsPlaceholderData,
    isExperimentsFetching,
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
