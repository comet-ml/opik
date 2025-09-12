import React, { useCallback, useMemo } from "react";
import { PieChart, Pie, Cell, ResponsiveContainer, Legend } from "recharts";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { ChartContainer, ChartTooltip } from "@/components/ui/chart";
import { getDefaultHashedColorsChartConfig } from "@/lib/charts";
import ChartTooltipContent, {
  ChartTooltipRenderHeaderArguments,
} from "@/components/shared/ChartTooltipContent/ChartTooltipContent";
import { useObserveResizeNode } from "@/hooks/useObserveResizeNode";
import NoData from "@/components/shared/NoData/NoData";
import { Experiment } from "@/types/datasets";

interface PieDataPoint {
  name: string;
  value: number;
  rangeStart: number;
  rangeEnd: number;
}

interface FeedbackScoresPieChartProps {
  name: string;
  experiments: Experiment[];
  scoreName: string;
  onRangeClick?: (rangeStart: number, rangeEnd: number) => void;
}

const RANGE_COUNT = 5;

const FeedbackScoresPieChart: React.FC<FeedbackScoresPieChartProps> = ({
  name,
  experiments,
  scoreName,
  onRangeClick,
}) => {
  const { ref } = useObserveResizeNode<HTMLDivElement>(() => {});

  const pieData = useMemo(() => {
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

    const minValue = Math.min(...values);
    const maxValue = Math.max(...values);
    const range = maxValue - minValue;
    const rangeWidth = range / RANGE_COUNT;

    const segments: PieDataPoint[] = [];
    for (let i = 0; i < RANGE_COUNT; i++) {
      const rangeStart = minValue + i * rangeWidth;
      const rangeEnd = minValue + (i + 1) * rangeWidth;
      const rangeLabel = `${rangeStart.toFixed(1)} - ${rangeEnd.toFixed(1)}`;
      const count = values.filter(
        (value) =>
          value >= rangeStart &&
          (i === RANGE_COUNT - 1 ? value <= rangeEnd : value < rangeEnd),
      ).length;

      if (count > 0) {
        segments.push({
          name: rangeLabel,
          value: count,
          rangeStart,
          rangeEnd,
        });
      }
    }

    return segments;
  }, [experiments, scoreName]);

  const config = useMemo(() => {
    return getDefaultHashedColorsChartConfig([scoreName]);
  }, [scoreName]);

  const colors = useMemo(() => {
    return pieData.map((_, index) => {
      const hue = (index * 137.5) % 360;
      return `hsl(${hue}, 70%, 60%)`;
    });
  }, [pieData]);

  const renderChartTooltipHeader = useCallback(
    ({ payload }: ChartTooltipRenderHeaderArguments) => {
      const { name, value } = payload[0].payload as PieDataPoint;
      const percentage = ((value / experiments.length) * 100).toFixed(1);
      return (
        <div className="comet-body-xs-accented truncate pb-1.5">
          {name} ({value} traces, {percentage}%)
        </div>
      );
    },
    [experiments.length],
  );

  const handlePieClick = useCallback(
    (data: PieDataPoint) => {
      if (onRangeClick && data.value > 0) {
        onRangeClick(data.rangeStart, data.rangeEnd);
      }
    },
    [onRangeClick],
  );

  const renderCustomizedLabel = useCallback(
    ({
      cx,
      cy,
      midAngle,
      innerRadius,
      outerRadius,
      percent,
    }: {
      cx: number;
      cy: number;
      midAngle: number;
      innerRadius: number;
      outerRadius: number;
      percent: number;
    }) => {
      if (percent < 0.05) return null;
      const RADIAN = Math.PI / 180;
      const radius = innerRadius + (outerRadius - innerRadius) * 0.5;
      const x = cx + radius * Math.cos(-midAngle * RADIAN);
      const y = cy + radius * Math.sin(-midAngle * RADIAN);

      return (
        <text
          x={x}
          y={y}
          fill="white"
          textAnchor={x > cx ? "start" : "end"}
          dominantBaseline="central"
          fontSize="12"
          fontWeight="bold"
        >
          {`${(percent * 100).toFixed(0)}%`}
        </text>
      );
    },
    [],
  );

  const noData = pieData.length === 0;

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
              <PieChart>
                <Pie
                  data={pieData}
                  cx="50%"
                  cy="50%"
                  labelLine={false}
                  label={renderCustomizedLabel}
                  outerRadius={80}
                  fill="#8884d8"
                  dataKey="value"
                  onClick={handlePieClick}
                  style={{ cursor: "pointer" }}
                >
                  {pieData.map((entry, index) => (
                    <Cell key={`cell-${index}`} fill={colors[index]} />
                  ))}
                </Pie>
                <ChartTooltip
                  isAnimationActive={false}
                  content={
                    <ChartTooltipContent
                      renderHeader={renderChartTooltipHeader}
                    />
                  }
                />
                <Legend
                  verticalAlign="bottom"
                  height={36}
                  formatter={(value, entry, index) => (
                    <span className="comet-body-xs text-foreground">
                      {value} ({pieData[index]?.value || 0})
                    </span>
                  )}
                />
              </PieChart>
            </ResponsiveContainer>
          </ChartContainer>
        )}
      </CardContent>
    </Card>
  );
};

export default FeedbackScoresPieChart;
