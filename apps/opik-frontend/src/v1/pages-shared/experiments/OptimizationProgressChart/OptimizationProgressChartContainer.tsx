import React, { useMemo } from "react";
import isNull from "lodash/isNull";

import { AggregatedCandidate } from "@/types/optimizations";
import { OPTIMIZATION_STATUS } from "@/types/optimizations";
import { IN_PROGRESS_OPTIMIZATION_STATUSES } from "@/lib/optimizations";
import NoData from "@/shared/NoData/NoData";
import { Card, CardContent, CardHeader, CardTitle } from "@/ui/card";
import { Spinner } from "@/ui/spinner";
import OptimizationProgressChartContent from "./OptimizationProgressChartContent";
import {
  buildCandidateChartData,
  type InProgressInfo,
} from "./optimizationChartUtils";

const INITIALIZING_MESSAGE = "Optimization process is initialized...";
const CALCULATING_BASELINE_MESSAGE = "Calculating baseline...";

type OptimizationProgressChartContainerProps = {
  candidates: AggregatedCandidate[];
  bestCandidateId?: string;
  objectiveName?: string;
  status?: OPTIMIZATION_STATUS;
  selectedTrialId?: string;
  onTrialSelect?: (trialId: string) => void;
  onTrialClick?: (candidateId: string) => void;
  isEvaluationSuite?: boolean;
  inProgressInfo?: InProgressInfo;
  isRunningMiniBatches?: boolean;
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
  inProgressInfo,
  isRunningMiniBatches,
}) => {
  const isInProgress =
    !!status && IN_PROGRESS_OPTIMIZATION_STATUSES.includes(status);

  const baselineMessage = candidates.some((c) => c.stepIndex === 0)
    ? CALCULATING_BASELINE_MESSAGE
    : INITIALIZING_MESSAGE;

  const chartData = useMemo(
    () =>
      buildCandidateChartData(
        candidates,
        isEvaluationSuite,
        isInProgress,
        inProgressInfo,
      ),
    [candidates, isEvaluationSuite, isInProgress, inProgressInfo],
  );

  const noData = useMemo(
    () => chartData.every((d) => isNull(d.value)),
    [chartData],
  );

  const renderContent = () => {
    if (!chartData.length || noData) {
      if (isInProgress) {
        return (
          <div className="flex min-h-32 flex-col items-center justify-center gap-2">
            <Spinner size="small" />
            <div className="comet-body-s text-muted-slate transition-opacity duration-300">
              {baselineMessage}
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
        isInProgress={isInProgress}
        inProgressInfo={inProgressInfo}
      />
    );
  };

  return (
    <Card className="h-[280px] min-w-[400px] flex-auto">
      <CardHeader className="space-y-0.5 px-4 pt-3">
        <CardTitle className="comet-body-s-accented flex items-center gap-2">
          Optimization progress
          {isInProgress && !noData && (
            <>
              <Spinner size="xs" />
              <span className="comet-body-xs font-normal text-muted-slate">
                {inProgressInfo
                  ? "Evaluating new candidate..."
                  : isRunningMiniBatches
                    ? "Looking for failing examples to reflect on..."
                    : "Generating new candidate..."}
              </span>
            </>
          )}
        </CardTitle>
      </CardHeader>
      <CardContent className="px-4 pb-3">{renderContent()}</CardContent>
    </Card>
  );
};

export default OptimizationProgressChartContainer;
