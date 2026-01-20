import React, { useCallback, useMemo } from "react";
import isNull from "lodash/isNull";
import isUndefined from "lodash/isUndefined";

import { formatDate } from "@/lib/date";
import { Experiment } from "@/types/datasets";
import { OPTIMIZATION_STATUS } from "@/types/optimizations";
import { IN_PROGRESS_OPTIMIZATION_STATUSES } from "@/lib/optimizations";
import { getFeedbackScoreValue } from "@/lib/feedback-scores";
import NoData from "@/components/shared/NoData/NoData";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Spinner } from "@/components/ui/spinner";
import useProgressSimulation from "@/hooks/useProgressSimulation";
import OptimizationProgressChartContent, {
  ChartData,
} from "./OptimizationProgressChartContent";

const OPTIMIZATION_TIPS = [
  "Running optimization trials...",
  "Evaluating prompt variations...",
  "Searching for better prompts...",
  "Analyzing trial results...",
  "Testing new configurations...",
  "Exploring the prompt space...",
];

type OptimizationProgressChartContainerProps = {
  experiments: Experiment[];
  bestEntityId?: string;
  objectiveName?: string;
  status?: OPTIMIZATION_STATUS;
};

const OptimizationProgressChartContainer: React.FC<
  OptimizationProgressChartContainerProps
> = ({ experiments, bestEntityId, status, objectiveName = "" }) => {
  const isInProgress =
    !!status && IN_PROGRESS_OPTIMIZATION_STATUSES.includes(status);

  const { message: currentTip } = useProgressSimulation({
    messages: OPTIMIZATION_TIPS,
    isPending: isInProgress,
    loop: true,
  });

  const chartData = useMemo(() => {
    const retVal: ChartData = {
      data: [],
      objectiveName,
    };

    experiments
      .slice()
      .sort((e1, e2) => e1.created_at.localeCompare(e2.created_at))
      .forEach((experiment) => {
        const value = getFeedbackScoreValue(
          experiment.feedback_scores ?? [],
          objectiveName,
        );

        retVal.data.push({
          entityId: experiment.id,
          entityName: experiment.name,
          createdDate: formatDate(experiment.created_at),
          value: isUndefined(value) ? null : value,
          allFeedbackScores:
            experiment.feedback_scores
              ?.map((score) => ({ name: score.name, value: score.value }))
              ?.filter((score) => score.name !== objectiveName) || [],
        });
      });

    return retVal;
  }, [experiments, objectiveName]);

  const isPending = !chartData;
  const noData = useMemo(() => {
    if (isPending) return false;

    return chartData.data.every((record) => isNull(record.value));
  }, [chartData?.data, isPending]);

  const renderContent = useCallback(() => {
    if (isPending) {
      return (
        <div className="flex size-full min-h-32 items-center justify-center">
          <Spinner />
        </div>
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
        bestEntityId={bestEntityId}
      />
    );
  }, [isPending, noData, chartData, bestEntityId, isInProgress, currentTip]);

  return (
    <Card className="h-[224px] min-w-[400px] flex-auto">
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
