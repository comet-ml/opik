import React, { useEffect, useMemo, useState } from "react";
import isNull from "lodash/isNull";

import { AggregatedCandidate } from "@/types/optimizations";
import { OPTIMIZATION_STATUS } from "@/types/optimizations";
import {
  IN_PROGRESS_OPTIMIZATION_STATUSES,
  INITIALIZED_STALL_THRESHOLD_SECONDS,
  isInitializationStalled,
} from "@/lib/optimizations";
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
const STALLED_MESSAGE =
  "This is taking longer than expected. The run may be queued behind other work, or it may have failed to launch — open the logs for details.";

type OptimizationProgressChartContainerProps = {
  candidates: AggregatedCandidate[];
  bestCandidateId?: string;
  objectiveName?: string;
  status?: OPTIMIZATION_STATUS;
  optimizationCreatedAt?: string;
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
  bestCandidateId,
  status,
  optimizationCreatedAt,
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

  // Recomputed from wall-clock on each render.
  const isStalled = isInitializationStalled(status, optimizationCreatedAt);

  // Crossing the threshold is time-based, not data-based, so drive a single
  // re-render exactly when it elapses instead of relying on an incidental poll
  // (which could stop re-rendering if this component were later memoized).
  const [, forceStallCheck] = useState(0);
  useEffect(() => {
    if (
      status !== OPTIMIZATION_STATUS.INITIALIZED ||
      !optimizationCreatedAt ||
      isStalled
    ) {
      return;
    }
    const elapsedMs = Date.now() - new Date(optimizationCreatedAt).getTime();
    const remainingMs = INITIALIZED_STALL_THRESHOLD_SECONDS * 1000 - elapsedMs;
    const timer = setTimeout(
      () => forceStallCheck((tick) => tick + 1),
      Math.max(0, remainingMs) + 250,
    );
    return () => clearTimeout(timer);
  }, [status, optimizationCreatedAt, isStalled]);

  const baselineMessage = candidates.some((c) => c.stepIndex === 0)
    ? CALCULATING_BASELINE_MESSAGE
    : INITIALIZING_MESSAGE;

  const chartData = useMemo(
    () =>
      buildCandidateChartData(
        candidates,
        isTestSuite,
        isInProgress,
        inProgressInfo,
      ),
    [candidates, isTestSuite, isInProgress, inProgressInfo],
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
            {/* Once stalled, drop the spinner — a spinning indicator next to a
                "may have failed to launch" warning is contradictory. */}
            {!isStalled && <Spinner size="small" />}
            <div className="comet-body-s text-muted-slate transition-opacity duration-300">
              {baselineMessage}
            </div>
            {isStalled && (
              <div className="comet-body-xs max-w-md text-balance text-center text-muted-slate">
                {STALLED_MESSAGE}
              </div>
            )}
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
