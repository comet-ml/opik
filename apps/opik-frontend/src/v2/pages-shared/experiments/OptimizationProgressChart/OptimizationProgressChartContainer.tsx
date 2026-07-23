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
  buildMiniBatchChartPoints,
  type InProgressInfo,
} from "./optimizationChartUtils";

const INITIALIZING_MESSAGE = "Optimization process is initialized...";
const CALCULATING_BASELINE_MESSAGE = "Calculating baseline...";

type OptimizationProgressChartContainerProps = {
  candidates: AggregatedCandidate[];
  /** Mini-batch screening evals (secondary series, visually distinct dots). */
  miniBatchCandidates?: AggregatedCandidate[];
  bestCandidateId?: string;
  objectiveName?: string;
  status?: OPTIMIZATION_STATUS;
  selectedTrialId?: string;
  onTrialSelect?: (trialId: string) => void;
  onTrialClick?: (candidateId: string) => void;
  isTestSuite?: boolean;
  inProgressInfo?: InProgressInfo;
  isRunningMiniBatches?: boolean;
  /** Skip the always-open best-trial card while the trial sidebar is open: it
   *  and the sidebar share one portal root, and no z-index sits both above the
   *  overview and below the panel, so it would otherwise float over the panel. */
  suppressBestTrialCard?: boolean;
};

const OptimizationProgressChartContainer: React.FC<
  OptimizationProgressChartContainerProps
> = ({
  candidates,
  miniBatchCandidates,
  bestCandidateId,
  status,
  objectiveName = "",
  selectedTrialId,
  onTrialSelect,
  onTrialClick,
  isTestSuite,
  inProgressInfo,
  isRunningMiniBatches,
  suppressBestTrialCard,
}) => {
  const isInProgress =
    !!status && IN_PROGRESS_OPTIMIZATION_STATUSES.includes(status);

  const baselineMessage = candidates.some((c) => c.stepIndex === 0)
    ? CALCULATING_BASELINE_MESSAGE
    : INITIALIZING_MESSAGE;

  const chartData = useMemo(() => {
    const fullPoints = buildCandidateChartData(
      candidates,
      isTestSuite,
      isInProgress,
      inProgressInfo,
    );
    // Mini-batch points join the same dataset (they share axes and the
    // hover/click machinery) but carry kind="minibatch" so they render as
    // small hollow dots, never join the trend line, and never become "best".
    const miniBatchPoints = buildMiniBatchChartPoints(
      miniBatchCandidates ?? [],
      candidates,
    );
    return [...fullPoints, ...miniBatchPoints];
  }, [
    candidates,
    miniBatchCandidates,
    isTestSuite,
    isInProgress,
    inProgressInfo,
  ]);

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
          message={
            status === OPTIMIZATION_STATUS.CANCELLED
              ? "This run was cancelled"
              : "No data to show"
          }
        />
      );
    }

    return (
      <OptimizationProgressChartContent
        chartData={chartData}
        candidates={candidates}
        miniBatchCandidates={miniBatchCandidates}
        bestCandidateId={bestCandidateId}
        objectiveName={objectiveName}
        selectedTrialId={selectedTrialId}
        onTrialSelect={onTrialSelect}
        onTrialClick={onTrialClick}
        isTestSuite={isTestSuite}
        isInProgress={isInProgress}
        inProgressInfo={inProgressInfo}
        suppressBestTrialCard={suppressBestTrialCard}
      />
    );
  };

  // data-chart-panel: the pinned best-trial card clamps itself to this panel's
  // borders (it may overhang the chart's inner padding).
  return (
    <Card data-chart-panel className="h-auto min-w-[400px] flex-auto">
      <CardHeader className="space-y-0.5 px-4 pb-0 pt-3">
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
