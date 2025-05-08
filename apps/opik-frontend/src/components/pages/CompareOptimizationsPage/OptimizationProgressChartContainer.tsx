import React, { useCallback, useMemo } from "react";
import isNull from "lodash/isNull";
import isUndefined from "lodash/isUndefined";

import { formatDate } from "@/lib/date";
import { Experiment } from "@/types/datasets";
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
};

const OptimizationProgressChartContainer: React.FC<
  OptimizationProgressChartContainerProps
> = ({ experiments, bestEntityId, objectiveName = "" }) => {
  const chartData = useMemo(() => {
    const retVal: ChartData = {
      data: [],
      line: objectiveName,
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
  }, [isPending, noData, chartData, bestEntityId]);

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
