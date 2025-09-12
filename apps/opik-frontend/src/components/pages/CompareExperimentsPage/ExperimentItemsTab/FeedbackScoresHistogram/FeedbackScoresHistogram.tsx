import React, { useCallback, useMemo, useState } from "react";
import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  ResponsiveContainer,
} from "recharts";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { DEFAULT_CHART_TICK } from "@/constants/chart";
import { ChartContainer, ChartTooltip } from "@/components/ui/chart";
import {
  getDefaultHashedColorsChartConfig,
  truncateChartLabel,
} from "@/lib/charts";
import ChartTooltipContent, {
  ChartTooltipRenderHeaderArguments,
} from "@/components/shared/ChartTooltipContent/ChartTooltipContent";
import { useObserveResizeNode } from "@/hooks/useObserveResizeNode";
import NoData from "@/components/shared/NoData/NoData";
import { Experiment } from "@/types/datasets";

interface HistogramDataPoint {
  range: string;
  count: number;
  rangeStart: number;
  rangeEnd: number;
}

interface FeedbackScoresHistogramProps {
  name: string;
  experiments: Experiment[];
  scoreName: string;
  onRangeClick?: (rangeStart: number, rangeEnd: number) => void;
}

const BIN_COUNT = 10;
const CHART_INNER_PADDING = 100;

const FeedbackScoresHistogram: React.FC<FeedbackScoresHistogramProps> = ({
  name,
  experiments,
  scoreName,
  onRangeClick,
}) => {
  const [width, setWidth] = useState<number>(0);
  const { ref } = useObserveResizeNode<HTMLDivElement>((node) =>
    setWidth(node.clientWidth),
  );

  const histogramData = useMemo(() => {
    // Extract all values for this score across all experiments
    const values: number[] = [];
    experiments.forEach((experiment) => {
      const score = experiment.feedback_scores?.find(
        (s) => s.name === scoreName,
      );
      if (score && typeof score.value === "number") {
        values.push(score.value);
      }
    });

    if (values.length === 0) {
      return [];
    }

    // Calculate min and max values
    const minValue = Math.min(...values);
    const maxValue = Math.max(...values);
    const range = maxValue - minValue;
    const binWidth = range / BIN_COUNT;

    // Create histogram bins
    const bins: HistogramDataPoint[] = [];
    for (let i = 0; i < BIN_COUNT; i++) {
      const rangeStart = minValue + i * binWidth;
      const rangeEnd = minValue + (i + 1) * binWidth;
      const rangeLabel = `${rangeStart.toFixed(2)} - ${rangeEnd.toFixed(2)}`;

      // Count values in this range
      const count = values.filter(
        (value) =>
          value >= rangeStart &&
          (i === BIN_COUNT - 1 ? value <= rangeEnd : value < rangeEnd),
      ).length;

      bins.push({
        range: rangeLabel,
        count,
        rangeStart,
        rangeEnd,
      });
    }

    return bins;
  }, [experiments, scoreName]);

  const config = useMemo(() => {
    return getDefaultHashedColorsChartConfig([scoreName]);
  }, [scoreName]);

  const renderChartTooltipHeader = useCallback(
    ({ payload }: ChartTooltipRenderHeaderArguments) => {
      const { range, count } = payload[0].payload as HistogramDataPoint;
      return (
        <div className="comet-body-xs-accented truncate pb-1.5">
          {range} ({count} traces)
        </div>
      );
    },
    [],
  );

  const truncateXAxisLabel = useCallback(
    (label: string) => {
      const labelsCount = histogramData.length;
      const xAxisWidth = width - CHART_INNER_PADDING;
      const maxLabelWidth = xAxisWidth / labelsCount;
      const maxTruncateLength = Math.floor(maxLabelWidth / 8);
      return truncateChartLabel(label, maxTruncateLength);
    },
    [histogramData.length, width],
  );

  const handleBarClick = useCallback(
    (data: HistogramDataPoint) => {
      if (onRangeClick && data.count > 0) {
        onRangeClick(data.rangeStart, data.rangeEnd);
      }
    },
    [onRangeClick],
  );

  const noData =
    histogramData.length === 0 || histogramData.every((bin) => bin.count === 0);

  return (
    <Card ref={ref}>
      <CardHeader className="space-y-0.5 px-5 py-4">
        <CardTitle className="comet-body-s-accented">{name}</CardTitle>
      </CardHeader>
      <CardContent className="px-5 pb-3">
        {noData ? (
          <NoData
            className="min-h-32 text-light-slate"
            message="No data to show"
          />
        ) : (
          <ChartContainer
            config={config}
            className="h-[var(--chart-height)] w-full"
          >
            <ResponsiveContainer width="100%" height="100%">
              <BarChart
                data={histogramData}
                margin={{
                  top: 5,
                  right: 5,
                  left: 5,
                  bottom: 20,
                }}
              >
                <CartesianGrid vertical={false} />
                <XAxis
                  dataKey="range"
                  axisLine={false}
                  tickLine={false}
                  dy={10}
                  tick={DEFAULT_CHART_TICK}
                  tickFormatter={truncateXAxisLabel}
                  angle={-45}
                  textAnchor="end"
                  height={60}
                />
                <YAxis
                  axisLine={false}
                  tickLine={false}
                  tick={DEFAULT_CHART_TICK}
                />
                <ChartTooltip
                  isAnimationActive={false}
                  cursor={{ fillOpacity: 0.6 }}
                  content={
                    <ChartTooltipContent
                      renderHeader={renderChartTooltipHeader}
                    />
                  }
                />
                <Bar
                  dataKey="count"
                  fill={config[scoreName]?.color || "#8884d8"}
                  onClick={handleBarClick}
                  style={{ cursor: "pointer" }}
                />
              </BarChart>
            </ResponsiveContainer>
          </ChartContainer>
        )}
      </CardContent>
    </Card>
  );
};

export default FeedbackScoresHistogram;
