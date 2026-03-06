import React, { useCallback, useMemo } from "react";
import isNull from "lodash/isNull";

import { AggregatedCandidate } from "@/types/optimizations";
import { OPTIMIZATION_STATUS } from "@/types/optimizations";
import { IN_PROGRESS_OPTIMIZATION_STATUSES } from "@/lib/optimizations";
import NoData from "@/components/shared/NoData/NoData";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Spinner } from "@/components/ui/spinner";
import useProgressSimulation from "@/hooks/useProgressSimulation";
import OptimizationProgressChartContent from "./OptimizationProgressChartContent";
import { buildCandidateChartData } from "./optimizationChartUtils";

const OPTIMIZATION_TIPS = [
  "Running optimization trials...",
  "Evaluating prompt variations...",
  "Searching for better prompts...",
  "Analyzing trial results...",
  "Testing new configurations...",
  "Exploring the prompt space...",
];

type OptimizationProgressChartContainerProps = {
  candidates: AggregatedCandidate[];
  bestCandidateId?: string;
  objectiveName?: string;
  status?: OPTIMIZATION_STATUS;
  selectedTrialId?: string;
  onTrialSelect?: (trialId: string) => void;
  onTrialClick?: (candidateId: string) => void;
  isEvaluationSuite?: boolean;
};

const OptimizationProgressChartContainer: React.FC<
  OptimizationProgressChartContainerProps
> = ({
  candidates,
  bestCandidateId,
  status,
  objectiveName = "",
  selectedTrialId,
  onTrialSelect,
  onTrialClick,
  isEvaluationSuite,
}) => {
  const isInProgress =
    !!status && IN_PROGRESS_OPTIMIZATION_STATUSES.includes(status);

  const { message: currentTip } = useProgressSimulation({
    messages: OPTIMIZATION_TIPS,
    isPending: isInProgress,
    loop: true,
  });

  const isOptimizationFinished =
    !!status && !IN_PROGRESS_OPTIMIZATION_STATUSES.includes(status);

  const chartData = useMemo(
    () => buildCandidateChartData(candidates, isOptimizationFinished),
    [candidates, isOptimizationFinished],
  );

  const noData = useMemo(
    () => chartData.every((d) => isNull(d.value)),
    [chartData],
  );

  const renderContent = useCallback(() => {
    if (!chartData.length) {
      if (isInProgress) {
        return (
          <div className="flex min-h-32 flex-col items-center justify-center gap-2">
            <Spinner size="small" />
            <div className="comet-body-s text-muted-slate transition-opacity duration-300">
              {currentTip}
            </div>
          </div>
        );
      }

      return (
        <NoData
          className="min-h-32 text-light-slate"
          message="No data to show"
        />
      );
    }

    if (noData) {
      if (isInProgress) {
        return (
          <div className="flex min-h-32 flex-col items-center justify-center gap-2">
            <Spinner size="small" />
            <div className="comet-body-s text-muted-slate transition-opacity duration-300">
              {currentTip}
            </div>
          </div>
        );
      }

      return (
        <NoData
          className="min-h-32 text-light-slate"
          message="No data to show"
        />
      );
    }

    return (
      <OptimizationProgressChartContent
        chartData={chartData}
        candidates={candidates}
        bestCandidateId={bestCandidateId}
        objectiveName={objectiveName}
        selectedTrialId={selectedTrialId}
        onTrialSelect={onTrialSelect}
        onTrialClick={onTrialClick}
        isEvaluationSuite={isEvaluationSuite}
      />
    );
  }, [
    chartData,
    candidates,
    noData,
    bestCandidateId,
    objectiveName,
    isInProgress,
    currentTip,
    selectedTrialId,
    onTrialSelect,
    onTrialClick,
    isEvaluationSuite,
  ]);

  return (
    <Card className="h-[280px] min-w-[400px] flex-auto">
      <CardHeader className="space-y-0.5 px-4 pt-3">
        <CardTitle className="comet-body-s-accented flex items-center gap-2">
          Optimization progress
          {isInProgress && !noData && <Spinner size="xs" />}
        </CardTitle>
      </CardHeader>
      <CardContent className="px-4 pb-3">{renderContent()}</CardContent>
    </Card>
  );
};

export default OptimizationProgressChartContainer;
