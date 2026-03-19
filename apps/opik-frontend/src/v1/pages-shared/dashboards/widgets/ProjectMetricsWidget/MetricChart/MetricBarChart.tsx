import React, { useCallback } from "react";
import { ValueType } from "recharts/types/component/DefaultTooltipContent";
import dayjs from "dayjs";

import { ChartConfig } from "@/ui/chart";
import { Spinner } from "@/ui/spinner";
import { INTERVAL_TYPE } from "@/api/projects/useProjectMetric";
import { ChartTooltipRenderHeaderArguments } from "@/shared/Charts/ChartTooltipContent/ChartTooltipContent";
import { formatDate } from "@/lib/date";
import { TransformedData } from "@/types/projects";
import type { LegendLabelAction } from "@/shared/Charts/LegendItem/LegendItem";
import BarChart from "@/shared/Charts/BarChart/BarChart";

const renderTooltipValue = ({ value }: { value: ValueType }) => value;

interface MetricBarChartProps {
  config: ChartConfig;
  interval: INTERVAL_TYPE;
  renderValue?: (data: { value: ValueType }) => ValueType;
  customYTickFormatter?: (value: number, maxDecimalLength?: number) => string;
  chartId: string;
  data: TransformedData[];
  isPending: boolean;
  labelActions?: Record<string, LegendLabelAction>;
  isAggregateTotal?: boolean;
}

const MetricBarChart: React.FunctionComponent<MetricBarChartProps> = ({
  config,
  interval,
  renderValue = renderTooltipValue,
  customYTickFormatter,
  chartId,
  isPending,
  data,
  labelActions,
  isAggregateTotal = false,
}) => {
  const renderChartTooltipHeader = useCallback(
    ({ payload }: ChartTooltipRenderHeaderArguments) => {
      if (isAggregateTotal) {
        return <div className="comet-body-xs mb-1 text-light-slate">Total</div>;
      }
      return (
        <div className="comet-body-xs mb-1 text-light-slate">
          {formatDate(payload?.[0]?.payload?.time, { utc: true })} UTC
        </div>
      );
    },
    [isAggregateTotal],
  );

  const xTickFormatter = useCallback(
    (val: string) => {
      if (isAggregateTotal) {
        return "";
      }

      if (interval === INTERVAL_TYPE.HOURLY) {
        return dayjs(val).utc().format("MM/DD hh:mm A");
      }

      return dayjs(val).utc().format("MM/DD");
    },
    [interval, isAggregateTotal],
  );

  if (isPending) {
    return (
      <div className="flex h-[var(--chart-height)] w-full items-center justify-center">
        <Spinner />
      </div>
    );
  }

  return (
    <BarChart
      chartId={chartId}
      config={config}
      data={data}
      xAxisKey="time"
      xTickFormatter={xTickFormatter}
      customYTickFormatter={customYTickFormatter}
      renderTooltipValue={renderValue}
      renderTooltipHeader={renderChartTooltipHeader}
      showLegend
      labelActions={labelActions}
    />
  );
};

export default MetricBarChart;
