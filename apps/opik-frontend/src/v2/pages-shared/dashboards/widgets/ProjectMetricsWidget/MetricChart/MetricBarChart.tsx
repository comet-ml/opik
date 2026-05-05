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
  customXTickFormatter?: (value: string) => string;
  chartId: string;
  data: TransformedData[];
  isPending: boolean;
  labelActions?: Record<string, LegendLabelAction>;
  isAggregateTotal?: boolean;
  showLegend?: boolean;
  tooltipPosition?: { x?: number; y?: number };
  targetTickCount?: number;
  xTickInterval?: number | "preserveStart" | "preserveEnd" | "preserveStartEnd";
  hideXAxis?: boolean;
  hideYAxis?: boolean;
}

const MetricBarChart: React.FunctionComponent<MetricBarChartProps> = ({
  config,
  interval,
  renderValue = renderTooltipValue,
  customYTickFormatter,
  customXTickFormatter,
  chartId,
  isPending,
  data,
  labelActions,
  isAggregateTotal = false,
  showLegend = true,
  tooltipPosition,
  targetTickCount,
  xTickInterval,
  hideXAxis = false,
  hideYAxis = false,
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
      xTickFormatter={customXTickFormatter ?? xTickFormatter}
      xTickInterval={xTickInterval}
      hideXAxis={hideXAxis}
      hideYAxis={hideYAxis}
      customYTickFormatter={customYTickFormatter}
      renderTooltipValue={renderValue}
      renderTooltipHeader={renderChartTooltipHeader}
      showLegend={showLegend}
      labelActions={labelActions}
      tooltipPosition={tooltipPosition}
      targetTickCount={targetTickCount}
    />
  );
};

export default MetricBarChart;
