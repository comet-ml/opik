import { useCallback, useEffect, useMemo } from "react";
import { useNavigate } from "@tanstack/react-router";
import { keepPreviousData } from "@tanstack/react-query";
import { ColumnSort } from "@tanstack/react-table";
import useLocalStorageState from "use-local-storage-state";
import { JsonParam, StringParam, useQueryParam } from "use-query-params";
import isArray from "lodash/isArray";

import {
  AggregatedFeedbackScore,
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_ID_ID,
  COLUMN_NAME_ID,
  ROW_HEIGHT,
} from "@/types/shared";
import { Experiment, EXPERIMENT_TYPE } from "@/types/datasets";
import {
  OPTIMIZATION_ACTIVE_REFETCH_INTERVAL,
  IN_PROGRESS_OPTIMIZATION_STATUSES,
  checkIsEvaluationSuite,
} from "@/lib/optimizations";
import useAppStore from "@/store/AppStore";
import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import useOptimizationById from "@/api/optimizations/useOptimizationById";
import useExperimentsList from "@/api/datasets/useExperimentsList";
import { useOptimizationScores } from "@/components/pages-shared/experiments/useOptimizationScores";
import {
  AggregatedCandidate,
  ExperimentOptimizationMetadata,
} from "@/types/optimizations";
import { getFeedbackScoreValue } from "@/lib/feedback-scores";

const MAX_EXPERIMENTS_LOADED = 1000;

const SELECTED_COLUMNS_KEY = "optimization-experiments-selected-columns-v3";
const COLUMNS_WIDTH_KEY = "optimization-experiments-columns-width";
const COLUMNS_ORDER_KEY = "optimization-experiments-columns-order";
const COLUMNS_SORT_KEY = "optimization-experiments-columns-sort-v2";
const ROW_HEIGHT_KEY = "optimization-experiments-row-height";

const DEFAULT_SELECTED_COLUMNS: string[] = [
  COLUMN_NAME_ID,
  "step",
  "objective_name",
  "runtime_cost",
  "latency",
  "trace_count",
  "trial_status",
  "created_at",
];

const DEFAULT_COLUMNS_ORDER: string[] = [
  COLUMN_NAME_ID,
  "step",
  COLUMN_ID_ID,
  "objective_name",
  "runtime_cost",
  "latency",
  "trace_count",
  "trial_status",
  "created_at",
];

const DEFAULT_SORTING: ColumnSort[] = [{ id: COLUMN_ID_ID, desc: false }];

const CLIENT_ONLY_SORT_COLUMNS = [
  "step",
  "trace_count",
  "trial_status",
  "runtime_cost",
  "latency",
];

const getOptimizationMetadata = (
  metadata: object | undefined,
  experimentId: string,
): ExperimentOptimizationMetadata | undefined => {
  if (!metadata) return undefined;
  const m = metadata as Record<string, unknown>;
  if (typeof m.step_index === "number") {
    return {
      step_index: m.step_index,
      candidate_id: (m.candidate_id as string) ?? "",
      parent_candidate_ids: (m.parent_candidate_ids as string[]) ?? [],
      configuration: m.configuration as
        | ExperimentOptimizationMetadata["configuration"]
        | undefined,
    };
  }
  // Old-style optimizer: each experiment is its own candidate
  // step_index will be assigned after sorting by creation time in aggregateCandidates
  return {
    step_index: -1,
    candidate_id: experimentId,
    parent_candidate_ids: [],
    configuration: m.configuration as
      | ExperimentOptimizationMetadata["configuration"]
      | undefined,
  };
};

const aggregateCandidates = (
  experiments: Experiment[],
  objectiveName: string | undefined,
): AggregatedCandidate[] => {
  const groups = new Map<
    string,
    {
      experiments: Experiment[];
      meta: ExperimentOptimizationMetadata;
    }
  >();

  for (const exp of experiments) {
    const meta = getOptimizationMetadata(exp.metadata, exp.id);
    if (!meta) continue;
    const key = meta.candidate_id;
    const existing = groups.get(key);
    if (existing) {
      existing.experiments.push(exp);
      // Keep the metadata with the lowest step_index — that's when the
      // candidate was first created, not a later re-evaluation step.
      if (meta.step_index >= 0 && meta.step_index < existing.meta.step_index) {
        existing.meta = meta;
      }
    } else {
      groups.set(key, { experiments: [exp], meta });
    }
  }

  const candidates: AggregatedCandidate[] = [];

  for (const [candidateId, group] of groups) {
    const exps = group.experiments.sort((a, b) =>
      a.created_at.localeCompare(b.created_at),
    );
    const meta = group.meta;

    let totalWeightedScore = 0;
    let totalWeightedCost = 0;
    let totalWeightedLatency = 0;
    let totalTraceCount = 0;
    let totalDatasetItemCount = 0;
    let hasScore = false;
    let hasCost = false;
    let hasLatency = false;

    for (const exp of exps) {
      const tc = exp.trace_count || 0;
      totalTraceCount += tc;
      totalDatasetItemCount += exp.dataset_item_count ?? tc;

      if (objectiveName) {
        const score = getFeedbackScoreValue(
          exp.feedback_scores ?? [],
          objectiveName,
        );
        if (score != null) {
          totalWeightedScore += score * tc;
          hasScore = true;
        }
      }

      if (exp.total_estimated_cost != null) {
        totalWeightedCost += exp.total_estimated_cost;
        hasCost = true;
      }

      if (exp.duration?.p50 != null) {
        totalWeightedLatency += (exp.duration.p50 / 1000) * tc;
        hasLatency = true;
      }
    }

    candidates.push({
      id: candidateId,
      candidateId,
      stepIndex: meta.step_index,
      parentCandidateIds: meta.parent_candidate_ids,
      trialNumber: 0, // assigned below
      score:
        hasScore && totalTraceCount > 0
          ? totalWeightedScore / totalTraceCount
          : undefined,
      runtimeCost:
        hasCost && totalTraceCount > 0
          ? totalWeightedCost / totalTraceCount
          : undefined,
      latencyP50:
        hasLatency && totalTraceCount > 0
          ? totalWeightedLatency / totalTraceCount
          : undefined,
      totalTraceCount,
      totalDatasetItemCount,
      experimentIds: exps.map((e) => e.id),
      name: exps[0].name,
      created_at: exps[0].created_at,
    });
  }

  // Sort by creation time and assign trial numbers
  candidates.sort((a, b) => a.created_at.localeCompare(b.created_at));

  // Assign incremental step indices for old-style experiments
  candidates.forEach((c, i) => {
    if (c.stepIndex === -1) {
      c.stepIndex = i;
      if (i > 0) {
        c.parentCandidateIds = [candidates[i - 1].candidateId];
      }
    }
  });

  candidates.forEach((c, i) => {
    c.trialNumber = i + 1;
  });

  return candidates;
};

const mergeExperimentScores = (
  feedbackScores: AggregatedFeedbackScore[] | undefined,
  experimentScores: AggregatedFeedbackScore[] | undefined,
): AggregatedFeedbackScore[] => {
  if (!experimentScores?.length) return [];
  const existingNames = new Set(feedbackScores?.map((s) => s.name));
  return experimentScores.filter((s) => !existingNames.has(s.name));
};

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
      sorting: sortedColumns
        .filter((column) => !CLIENT_ONLY_SORT_COLUMNS.includes(column.id))
        .map((column) => {
          if (column.id === "objective_name") {
            return {
              ...column,
              id: `${COLUMN_FEEDBACK_SCORES_ID}.${optimization?.objective_name}`,
            };
          }
          return column;
        }),
      types: [EXPERIMENT_TYPE.TRIAL],
      page: 1,
      size: MAX_EXPERIMENTS_LOADED,
    },
    {
      placeholderData: keepPreviousData,
      refetchInterval: OPTIMIZATION_ACTIVE_REFETCH_INTERVAL,
    },
  );

  const isInProgress =
    !!optimization?.status &&
    IN_PROGRESS_OPTIMIZATION_STATUSES.includes(optimization.status);

  // Lightweight query: fetch the most recent mutation experiment to detect
  // in-progress work. Mutation experiments are created when the optimizer
  // explores a new candidate, so they indicate upcoming work on the chart.
  const { data: latestExpData } = useExperimentsList(
    {
      workspaceName,
      optimizationId: optimizationId,
      types: [EXPERIMENT_TYPE.MUTATION],
      sorting: [{ id: "created_at", desc: true }],
      forceSorting: true,
      page: 1,
      size: 1,
      queryKey: "experiments-latest-mutation",
    },
    {
      enabled: !!optimizationId && isInProgress,
      refetchInterval: OPTIMIZATION_ACTIVE_REFETCH_INTERVAL,
    },
  );

  const sortableBy: string[] = useMemo(
    () =>
      isArray(data?.sortable_by)
        ? [...data.sortable_by, "objective_name", ...CLIENT_ONLY_SORT_COLUMNS]
        : [],
    [data?.sortable_by],
  );

  const title = optimization?.name || optimizationId;
  const noData = !search;
  const noDataText = noData ? "There are no trials yet" : "No search results";

  const isEvaluationSuite = useMemo(
    () => checkIsEvaluationSuite(data?.content ?? []),
    [data?.content],
  );

  const experiments = useMemo(() => {
    const content = data?.content ?? [];
    const objectiveName = optimization?.objective_name;

    return content.map((experiment) => {
      const additional = mergeExperimentScores(
        experiment.feedback_scores,
        experiment.experiment_scores,
      );

      let feedbackScores = additional.length
        ? [...(experiment.feedback_scores ?? []), ...additional]
        : experiment.feedback_scores;

      if (isEvaluationSuite && objectiveName && feedbackScores) {
        feedbackScores = feedbackScores.filter((s) => s.name === objectiveName);
      }

      if (!additional.length && !isEvaluationSuite) return experiment;
      return {
        ...experiment,
        feedback_scores: feedbackScores,
      };
    });
  }, [data?.content, isEvaluationSuite, optimization?.objective_name]);

  useEffect(() => {
    title &&
      setBreadcrumbParam("optimizationsCompare", "optimizationsCompare", title);
    return () =>
      setBreadcrumbParam("optimizationsCompare", "optimizationsCompare", "");
  }, [title, setBreadcrumbParam]);

  const candidates = useMemo(
    () => aggregateCandidates(experiments, optimization?.objective_name),
    [experiments, optimization?.objective_name],
  );

  // Derive in-progress candidate info from the latest mutation experiment.
  // Mutation experiments carry parent_candidate_ids from the optimizer.
  // The ghost step is always parent step + 1 (each mutation is one generation ahead).
  // If the mutation's candidate already has a score, the work is done — no ghost.
  const inProgressInfo = useMemo(() => {
    if (!isInProgress) return undefined;

    const latestMutation = latestExpData?.content?.[0];
    if (!latestMutation) return undefined;

    const meta = getOptimizationMetadata(
      latestMutation.metadata,
      latestMutation.id,
    );
    if (!meta || !meta.parent_candidate_ids.length) return undefined;

    const existingCandidate = candidates.find(
      (c) => c.candidateId === meta.candidate_id,
    );
    if (existingCandidate?.score != null) return undefined;

    const parentSteps = candidates
      .filter((c) => meta.parent_candidate_ids.includes(c.candidateId))
      .map((c) => c.stepIndex);
    if (!parentSteps.length) return undefined;

    return {
      stepIndex: Math.max(...parentSteps) + 1,
      parentCandidateIds: meta.parent_candidate_ids,
    };
  }, [isInProgress, latestExpData?.content, candidates]);

  const rows = useMemo(
    () =>
      candidates.filter(({ name }) =>
        name.toLowerCase().includes(search!.toLowerCase()),
      ),
    [candidates, search],
  );

  const { scoreMap, baseScore, bestExperiment } = useOptimizationScores(
    experiments,
    optimization?.objective_name,
  );

  const baselineCandidate = useMemo(
    () => candidates.find((c) => c.stepIndex === 0),
    [candidates],
  );

  const bestCandidate = useMemo(() => {
    if (!candidates.length) return undefined;

    return candidates.reduce<AggregatedCandidate | undefined>((best, c) => {
      if (c.score == null) return best;
      if (!best || best.score == null || c.score > best.score) return c;
      return best;
    }, undefined);
  }, [candidates]);

  const baselineExperiment = useMemo(() => {
    if (!experiments.length) return undefined;
    const sortedRows = experiments
      .slice()
      .sort((e1, e2) => e1.created_at.localeCompare(e2.created_at));
    return sortedRows[0];
  }, [experiments]);

  const handleRowClick = useCallback(
    (row: AggregatedCandidate) => {
      navigate({
        to: "/$workspaceName/optimizations/$datasetId/$optimizationId/compare",
        params: {
          datasetId: optimization?.dataset_id ?? "",
          optimizationId,
          workspaceName,
        },
        search: {
          trials: row.experimentIds,
        },
      });
    },
    [navigate, workspaceName, optimizationId, optimization?.dataset_id],
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
    candidates,
    isEvaluationSuite,
    rows,
    title,
    noDataText,
    scoreMap,
    baseScore,
    bestExperiment,
    bestCandidate,
    baselineCandidate,
    baselineExperiment,
    inProgressInfo,
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
