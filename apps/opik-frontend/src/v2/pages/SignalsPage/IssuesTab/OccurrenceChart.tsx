import React, { useMemo } from "react";
import LineChart from "@/shared/Charts/LineChart/LineChart";
import { ChartConfig } from "@/ui/chart";
import { formatDate } from "@/lib/date";
import { AgentInsightsIssueDetail } from "@/types/signals";

type OccurrenceChartProps = {
  data: AgentInsightsIssueDetail[];
};

const config: ChartConfig = {
  count: {
    label: "Occurrences",
    color: "var(--color-primary)",
  },
};

const OccurrenceChart: React.FC<OccurrenceChartProps> = ({ data }) => {
  const chartData = useMemo(
    () => data.map((point) => ({ time: point.report_day, count: point.count })),
    [data],
  );

  return (
    <LineChart
      chartId="signals-occurrence-over-time"
      config={config}
      data={chartData}
      xAxisKey="time"
      xTickFormatter={(value) => formatDate(value, { format: "D MMM" })}
      showLegend={false}
      className="h-[140px] w-full"
    />
  );
};

export default OccurrenceChart;
