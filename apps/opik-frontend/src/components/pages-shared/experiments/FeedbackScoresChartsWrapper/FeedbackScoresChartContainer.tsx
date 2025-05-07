import React, { useCallback, useMemo, useState } from "react";
import isEmpty from "lodash/isEmpty";

import { Dataset } from "@/types/datasets";
import NoData from "@/components/shared/NoData/NoData";
import { useObserveResizeNode } from "@/hooks/useObserveResizeNode";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { cn } from "@/lib/utils";
import { Spinner } from "@/components/ui/spinner";
import FeedbackScoresChartContent, {
  ChartData,
} from "./FeedbackScoresChartContent";

type FeedbackScoresChartContainerProps = {
  className: string;
  chartData?: ChartData;
  chartId: string;
  dataset: Dataset;
  isAverageScores: boolean;
};

const FeedbackScoresChartContainer: React.FC<
  FeedbackScoresChartContainerProps
> = ({ chartData, chartId, dataset, className, isAverageScores }) => {
  const isPending = !chartData;
  const noData = useMemo(() => {
    if (isPending) return false;

    return chartData.data.every((record) => isEmpty(record.scores));
  }, [chartData?.data, isPending]);

  const [width, setWidth] = useState<number>(0);
  const { ref } = useObserveResizeNode<HTMLDivElement>((node) =>
    setWidth(node.clientWidth),
  );

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
          message="No scores to show"
        />
      );
    }

    return (
      <FeedbackScoresChartContent
        chartId={chartId}
        chartData={chartData}
        containerWidth={width}
      />
    );
  }, [isPending, noData, chartData, chartId, width]);

  return (
    <Card className={cn("min-w-[400px]", className)} ref={ref}>
      <CardHeader className="space-y-0.5 px-4 pt-3">
        <CardTitle className="comet-body-s-accented">{dataset.name}</CardTitle>
        {isAverageScores && (
          <CardDescription className="comet-body-xs text-xs">
            Average scores
          </CardDescription>
        )}
      </CardHeader>
      <CardContent className="px-4 pb-3">{renderContent()}</CardContent>
    </Card>
  );
};

export default FeedbackScoresChartContainer;
