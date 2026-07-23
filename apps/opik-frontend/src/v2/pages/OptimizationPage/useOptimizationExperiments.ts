import { useCallback, useMemo } from "react";
import { useParams } from "@tanstack/react-router";
import { keepPreviousData } from "@tanstack/react-query";

import { EXPERIMENT_TYPE } from "@/types/datasets";
import {
  OPTIMIZATION_ACTIVE_REFETCH_INTERVAL,
  IN_PROGRESS_OPTIMIZATION_STATUSES,
  MAX_EXPERIMENTS_LOADED,
  CANDIDATE_SORT_FIELD_MAP,
  checkIsTestSuite,
  getBaselineCandidate,
  aggregateCandidates,
  mergeExperimentScores,
  splitExperimentsByEvalType,
} from "@/lib/optimizations";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";

import useOptimizationById from "@/api/optimizations/useOptimizationById";
import useExperimentsList from "@/api/datasets/useExperimentsList";
import { useOptimizationScores } from "@/v2/pages-shared/experiments/useOptimizationScores";
import { AggregatedCandidate } from "@/types/optimizations";
import { getOptimizationRefetchInterval } from "./optimizationOverviewHelpers";

export const useOptimizationExperiments = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const activeProjectId = useActiveProjectId();

  const { optimizationId } = useParams({
    select: (params) => params,
    from: "/workspaceGuard/$workspaceName/projects/$projectId/optimizations/$optimizationId",
  });

  const {
    data: optimization,
    isPending: isOptimizationPending,
    refetch: refetchOptimization,
  } = useOptimizationById(
    { optimizationId },
    {
      placeholderData: keepPreviousData,
      enabled: !!optimizationId,
      // Poll only while the run is active (reads the freshest status).
      refetchInterval: (query) =>
        getOptimizationRefetchInterval(query.state.data?.status),
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
      projectId: activeProjectId ?? undefined,
      optimizationId: optimizationId,
      sorting: [{ id: "created_at", desc: false }],
      forceSorting: true,
      // Mini-batches are loaded alongside full evals but are split into their
      // own pool below — they must never enter candidate aggregation or any
      // best-score selection (5-item screening scores are not comparable with
      // 30-item full evaluations).
      types: [EXPERIMENT_TYPE.TRIAL, EXPERIMENT_TYPE.MINI_BATCH],
      page: 1,
      size: MAX_EXPERIMENTS_LOADED,
    },
    {
      placeholderData: keepPreviousData,
      refetchInterval: getOptimizationRefetchInterval(optimization?.status),
    },
  );

  const isInProgress =
    !!optimization?.status &&
    IN_PROGRESS_OPTIMIZATION_STATUSES.includes(optimization.status);

  const { data: latestExperimentData } = useExperimentsList(
    {
      workspaceName,
      projectId: activeProjectId ?? undefined,
      optimizationId: optimizationId,
      types: [
        EXPERIMENT_TYPE.TRIAL,
        EXPERIMENT_TYPE.MINI_BATCH,
        EXPERIMENT_TYPE.MUTATION,
      ],
      sorting: [{ id: "created_at", desc: true }],
      forceSorting: true,
      page: 1,
      size: 1,
      queryKey: "experiments-latest",
    },
    {
      enabled: !!optimizationId && isInProgress,
      refetchInterval: OPTIMIZATION_ACTIVE_REFETCH_INTERVAL,
    },
  );

  const sortableBy: string[] = useMemo(
    () => Object.keys(CANDIDATE_SORT_FIELD_MAP),
    [],
  );

  const isTestSuite = useMemo(
    () => checkIsTestSuite(data?.content ?? []),
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

      if (isTestSuite && objectiveName && feedbackScores) {
        feedbackScores = feedbackScores.filter((s) => s.name === objectiveName);
      }

      if (!additional.length && !isTestSuite) return experiment;
      return {
        ...experiment,
        feedback_scores: feedbackScores,
      };
    });
  }, [data?.content, isTestSuite, optimization?.objective_name]);

  // Split BEFORE aggregation: mini-batch experiments for the same candidate
  // would otherwise blend into its full-eval score (aggregation is
  // trace-weighted), corrupting the chart and every "best" computation.
  const { fullEvalExperiments, miniBatchExperiments } = useMemo(
    () => splitExperimentsByEvalType(experiments),
    [experiments],
  );

  const candidates = useMemo(
    () =>
      aggregateCandidates(fullEvalExperiments, optimization?.objective_name),
    [fullEvalExperiments, optimization?.objective_name],
  );

  // Mini-batch screening evals, aggregated per candidate within their own
  // pool. Rendered as a visually distinct secondary chart series; never a
  // source for best prompt / best score.
  const miniBatchCandidates = useMemo(
    () =>
      aggregateCandidates(miniBatchExperiments, optimization?.objective_name),
    [miniBatchExperiments, optimization?.objective_name],
  );

  const inProgressInfo = useMemo(() => {
    if (!isInProgress) return undefined;

    const unscoredCandidate = candidates.find(
      (c) => c.score == null && c.parentCandidateIds.length > 0,
    );
    if (unscoredCandidate) {
      return {
        candidateId: unscoredCandidate.candidateId,
        stepIndex: unscoredCandidate.stepIndex,
        parentCandidateIds: unscoredCandidate.parentCandidateIds,
      };
    }

    return undefined;
  }, [isInProgress, candidates]);

  const isRunningMiniBatches = useMemo(() => {
    if (!isInProgress) return false;

    const latest = latestExperimentData?.content?.[0];
    return latest?.type === EXPERIMENT_TYPE.MINI_BATCH;
  }, [isInProgress, latestExperimentData?.content]);

  const { scoreMap, baseScore, bestExperiment } = useOptimizationScores(
    fullEvalExperiments,
    optimization?.objective_name,
  );

  const baselineCandidate = useMemo(
    () => getBaselineCandidate(candidates),
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

  // Mirror the SDK tie policy (OPIK-7038, utils/scoring.improves_over): a
  // candidate must STRICTLY beat the baseline to count as an improvement — a
  // tie keeps the seed/original prompt as the result. Compare best vs baseline
  // on the SAME aggregated-candidate path (not the experiment-derived
  // `baseScore`, which defaults to 0 and can diverge), so an unscored baseline
  // reads as `undefined` ("not comparable yet") rather than a real 0. When this
  // is `false` the best-scoring trial did NOT win: the original prompt was kept.
  const improvedOverBaseline = useMemo<boolean | undefined>(() => {
    const bestScore = bestCandidate?.score;
    const baselineScore = baselineCandidate?.score;
    if (bestScore == null || baselineScore == null) return undefined;
    return bestScore > baselineScore;
  }, [bestCandidate, baselineCandidate]);

  const baselineExperiment = useMemo(() => {
    // Baseline = earliest FULL evaluation; a mini-batch screening eval that
    // happens to be recorded first must never stand in as the baseline.
    if (!fullEvalExperiments.length) return undefined;
    const sortedRows = fullEvalExperiments
      .slice()
      .sort((e1, e2) => e1.created_at.localeCompare(e2.created_at));
    return sortedRows[0];
  }, [fullEvalExperiments]);

  const handleRefresh = useCallback(() => {
    refetchOptimization();
    refetchExperiments();
  }, [refetchOptimization, refetchExperiments]);

  return {
    workspaceName,
    optimizationId,
    optimization,
    experiments,
    fullEvalExperiments,
    candidates,
    miniBatchCandidates,
    isTestSuite,
    scoreMap,
    baseScore,
    bestExperiment: bestExperiment,
    bestCandidate,
    improvedOverBaseline,
    baselineCandidate,
    baselineExperiment,
    inProgressInfo,
    isRunningMiniBatches,
    sortableBy,
    isOptimizationPending,
    isExperimentsPending,
    isExperimentsPlaceholderData,
    isExperimentsFetching,
    handleRefresh,
  };
};
