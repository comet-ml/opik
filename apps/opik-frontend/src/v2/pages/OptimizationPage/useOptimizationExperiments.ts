import { useCallback, useMemo } from "react";
import { useParams } from "@tanstack/react-router";
import { keepPreviousData } from "@tanstack/react-query";

import { EXPERIMENT_TYPE } from "@/types/datasets";
import {
  OPTIMIZATION_ACTIVE_REFETCH_INTERVAL,
  IN_PROGRESS_OPTIMIZATION_STATUSES,
  MAX_EXPERIMENTS_LOADED,
  CANDIDATE_SORT_FIELD_MAP,
  checkIsEvaluationSuite,
  getBaselineCandidate,
  aggregateCandidates,
  mergeExperimentScores,
} from "@/lib/optimizations";
import useAppStore from "@/store/AppStore";

import useOptimizationById from "@/api/optimizations/useOptimizationById";
import useExperimentsList from "@/api/datasets/useExperimentsList";
import { useOptimizationScores } from "@/v2/pages-shared/experiments/useOptimizationScores";
import { AggregatedCandidate } from "@/types/optimizations";

export const useOptimizationExperiments = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const { optimizationId } = useParams({
    select: (params) => params,
    from: "/workspaceGuard/$workspaceName/optimizations/$optimizationId",
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
      sorting: [{ id: "created_at", desc: false }],
      forceSorting: true,
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

  const { data: latestExperimentData } = useExperimentsList(
    {
      workspaceName,
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

  const candidates = useMemo(
    () => aggregateCandidates(experiments, optimization?.objective_name),
    [experiments, optimization?.objective_name],
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
    experiments,
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

  const baselineExperiment = useMemo(() => {
    if (!experiments.length) return undefined;
    const sortedRows = experiments
      .slice()
      .sort((e1, e2) => e1.created_at.localeCompare(e2.created_at));
    return sortedRows[0];
  }, [experiments]);

  const handleRefresh = useCallback(() => {
    refetchOptimization();
    refetchExperiments();
  }, [refetchOptimization, refetchExperiments]);

  return {
    workspaceName,
    optimizationId,
    optimization,
    experiments,
    candidates,
    isEvaluationSuite,
    scoreMap,
    baseScore,
    bestExperiment: bestExperiment,
    bestCandidate,
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
