import React, { useCallback, useMemo } from "react";
import isNull from "lodash/isNull";
import isUndefined from "lodash/isUndefined";
import { Clock } from "lucide-react";

import { formatDate } from "@/lib/date";
import { Experiment } from "@/types/datasets";
import { OPTIMIZATION_STATUS } from "@/types/optimizations";
import { getFeedbackScoreValue } from "@/lib/feedback-scores";
import NoData from "@/components/shared/NoData/NoData";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Spinner } from "@/components/ui/spinner";
import OptimizationProgressChartContent, {
  ChartData,
} from "./OptimizationProgressChartContent";

type OptimizationProgressChartContainerProps = {
  experiments: Experiment[];
  bestEntityId?: string;
  objectiveName?: string;
  status?: OPTIMIZATION_STATUS;
};

const OptimizationProgressChartContainer: React.FC<
  OptimizationProgressChartContainerProps
> = ({ experiments, bestEntityId, objectiveName = "", status }) => {
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
      const isInProgress =
        status === OPTIMIZATION_STATUS.RUNNING ||
        status === OPTIMIZATION_STATUS.INITIALIZED;

      return (
        <NoData
          className="min-h-32 text-light-slate"
          icon={
            isInProgress ? <Clock className="text-muted-slate" /> : undefined
          }
          message={
            isInProgress ? "Results will appear shortly" : "No data to show"
          }
        />
      );
    }

    return (
      <OptimizationProgressChartContent
        chartData={chartData}
        bestEntityId={bestEntityId}
      />
    );
  }, [isPending, noData, chartData, bestEntityId, status]);

  return (
    <Card className="h-[224px] min-w-[400px] flex-auto">
      <CardHeader className="space-y-0.5 px-4 pt-3">
        <CardTitle className="comet-body-s-accented">
          Optimization progress
        </CardTitle>
      </CardHeader>
      <CardContent className="px-4 pb-3">{renderContent()}</CardContent>
    </Card>
  );
};

export default OptimizationProgressChartContainer;
