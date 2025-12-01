import React from "react";

import { cn } from "@/lib/utils";
import FeedbackScoresChartContainer from "@/components/pages-shared/experiments/FeedbackScoresChartsWrapper/FeedbackScoresChartContainer";
import { ChartData } from "@/components/pages-shared/experiments/FeedbackScoresChartsWrapper/FeedbackScoresChartContent";

type FeedbackScoresChartsWrapperProps = {
  chartsData: ChartData[];
  isAggregationScores?: boolean;
  noDataComponent?: React.ReactNode;
};

const FeedbackScoresChartsWrapper = ({
  chartsData,
  isAggregationScores = false,
  noDataComponent,
}: FeedbackScoresChartsWrapperProps) => {
  const chartClassName =
    chartsData.length === 1
      ? "w-full"
      : chartsData.length === 2
        ? "basis-1/2"
        : "basis-[520px]";

  return (
    <div className={cn("flex items-center gap-4 overflow-y-auto mb-4")}>
      {chartsData.length === 0 && noDataComponent
        ? noDataComponent
        : chartsData.map((data, index) => (
            <FeedbackScoresChartContainer
              key={data.id}
              className={chartClassName}
              chartData={chartsData[index]}
              chartId={data.id}
              chartName={data.name}
              subtitle={isAggregationScores ? "Aggregation scores" : undefined}
            />
          ))}
    </div>
  );
};

export default FeedbackScoresChartsWrapper;
