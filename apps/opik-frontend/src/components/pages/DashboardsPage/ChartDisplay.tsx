import React, { useMemo } from "react";
import { Activity } from "lucide-react";

import { DashboardChart, ChartDataRequest, TimeInterval } from "@/types/dashboards";
import useAppStore from "@/store/AppStore";
import useChartDataQuery from "@/api/dashboards/useChartDataQuery";
import Loader from "@/components/shared/Loader/Loader";
import ChartPreview from "./ChartPreview";

type ChartDisplayProps = {
  chart: DashboardChart;
  dashboardId: string;
  interval: TimeInterval;
};

const ChartDisplay: React.FunctionComponent<ChartDisplayProps> = ({
  chart,
  dashboardId,
  interval,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  // Default to last 7 days for now
  const chartRequest: ChartDataRequest = useMemo(() => {
    const now = new Date();
    const weekAgo = new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000);

    return {
      interval: interval,
      interval_start: weekAgo.toISOString(),
      interval_end: now.toISOString(),
    };
  }, [interval]);

  const { data: chartData, isPending } = useChartDataQuery(
    {
      dashboardId,
      chartId: chart.id!,
      request: chartRequest,
      workspaceName,
    },
    {
      enabled: Boolean(chart.id),
    },
  );

  if (isPending) {
    return (
      <div className="flex h-64 items-center justify-center">
        <Loader />
      </div>
    );
  }

  if (!chartData || !chartData.series || chartData.series.length === 0) {
    return (
      <div className="flex h-64 items-center justify-center">
        <div className="text-center">
          <Activity className="mx-auto mb-2 size-8 text-light-slate" />
          <p className="comet-body-s text-light-slate">No data available</p>
        </div>
      </div>
    );
  }

  // Use the ChartPreview component to render the actual chart
  return (
    <ChartPreview
      data={chartData}
      chartType={chart.chart_type}
      dataSeries={chart.data_series || []}
      isLoading={isPending}
    />
  );
};

export default ChartDisplay;



