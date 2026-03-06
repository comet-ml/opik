import React, { useCallback, useMemo } from "react";
import { useNavigate } from "@tanstack/react-router";

import Loader from "@/components/shared/Loader/Loader";
import OptimizationProgressChartContainer from "@/components/pages-shared/experiments/OptimizationProgressChart";
import TrialConfigurationSection from "@/components/pages-shared/experiments/TrialConfigurationSection";
import { IN_PROGRESS_OPTIMIZATION_STATUSES } from "@/lib/optimizations";
import { useCompareOptimizationsData } from "./useCompareOptimizationsData";
import CompareOptimizationsHeader from "./CompareOptimizationsHeader";
import OptimizationKPICards from "./OptimizationKPICards";

const CompareOptimizationsPage: React.FC = () => {
  const navigate = useNavigate();

  const {
    workspaceName,
    optimizationId,
    optimization,
    experiments,
    candidates,
    title,

    bestCandidate,
    baselineCandidate,
    baselineExperiment,
    isEvaluationSuite,
    isOptimizationPending,
    isExperimentsPending,
  } = useCompareOptimizationsData();

  const bestExperiment = useMemo(() => {
    if (!bestCandidate || !experiments.length) return undefined;
    const ids = new Set(bestCandidate.experimentIds);
    return experiments.find((e) => ids.has(e.id));
  }, [bestCandidate, experiments]);

  const handleTrialClick = useCallback(
    (candidateId: string) => {
      const candidate = candidates.find((c) => c.candidateId === candidateId);
      if (!candidate) return;
      navigate({
        to: "/$workspaceName/optimizations/$datasetId/$optimizationId/compare",
        params: {
          datasetId: optimization?.dataset_id ?? "",
          optimizationId,
          workspaceName,
        },
        search: {
          trials: candidate.experimentIds,
          trialNumber: candidate.trialNumber,
        },
      });
    },
    [
      candidates,
      navigate,
      optimizationId,
      optimization?.dataset_id,
      workspaceName,
    ],
  );

  if (isOptimizationPending || isExperimentsPending) {
    return <Loader />;
  }

  const isStudioOptimization = Boolean(optimization?.studio_config);
  const canRerun =
    isStudioOptimization &&
    Boolean(optimization?.id) &&
    optimization?.status &&
    !IN_PROGRESS_OPTIMIZATION_STATUSES.includes(optimization.status);

  return (
    <div className="flex h-full flex-col pt-6">
      <div className="shrink-0 pb-4">
        <CompareOptimizationsHeader
          title={title}
          status={optimization?.status}
          optimizationId={optimization?.id}
          isStudioOptimization={isStudioOptimization}
          canRerun={canRerun}
          bestExperiment={bestExperiment}
        />
      </div>

      <div className="shrink-0 pb-4">
        <OptimizationKPICards
          experiments={experiments}
          baselineCandidate={baselineCandidate}
          bestCandidate={bestCandidate}
          isEvaluationSuite={isEvaluationSuite}
        />
      </div>

      <div className="shrink-0 pb-4">
        <OptimizationProgressChartContainer
          candidates={candidates}
          bestCandidateId={bestCandidate?.candidateId}
          objectiveName={optimization?.objective_name}
          status={optimization?.status}
          onTrialClick={handleTrialClick}
          isEvaluationSuite={isEvaluationSuite}
        />
      </div>

      {bestExperiment && (
        <div className="shrink-0 pb-4">
          <TrialConfigurationSection
            experiments={[bestExperiment]}
            title="Best Configuration"
            referenceExperiment={baselineExperiment}
            studioConfig={optimization?.studio_config}
          />
        </div>
      )}
    </div>
  );
};

export default CompareOptimizationsPage;
