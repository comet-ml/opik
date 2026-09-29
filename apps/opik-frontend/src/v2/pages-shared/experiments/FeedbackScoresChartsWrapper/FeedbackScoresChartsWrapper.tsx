import React from "react";

import FeedbackScoresChartContainer from "@/v2/pages-shared/experiments/FeedbackScoresChartsWrapper/FeedbackScoresChartContainer";
import { ChartData } from "@/v2/pages-shared/experiments/FeedbackScoresChartsWrapper/FeedbackScoresChartContent";

type FeedbackScoresChartsWrapperProps = {
  chartsData: ChartData[];
  areAggregatedScores?: boolean;
  noDataComponent?: React.ReactNode;
};

const FeedbackScoresChartsWrapper = ({
  chartsData,
  areAggregatedScores = false,
  noDataComponent,
}: FeedbackScoresChartsWrapperProps) => {
  return (
    <div className="mb-4 grid grid-cols-[repeat(auto-fit,minmax(400px,1fr))] gap-4">
      {chartsData.length === 0 && noDataComponent
        ? noDataComponent
        : chartsData.map((data, index) => (
            <FeedbackScoresChartContainer
              key={data.id}
              chartData={chartsData[index]}
              chartId={data.id}
              chartName={data.name}
              subtitle={areAggregatedScores ? "Aggregated scores" : undefined}
            />
          ))}
    </div>
  );
};

export default FeedbackScoresChartsWrapper;
