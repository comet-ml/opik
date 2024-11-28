import React, { useMemo } from "react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  CartesianGrid,
  Line,
  LineChart,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { ProjectMetricTrace, ProjectMetricValue } from "@/types/projects";
import { ChartContainer, ChartTooltip } from "@/components/ui/chart";
import MetricChartTooltipContent from "@/components/pages/TracesPage/MetricsTab/MetricChart/MetricChartTooltipContent";
import dayjs from "dayjs";
import { DEFAULT_CHART_TICK } from "@/constants/chart";
import {
  getDefaultChartYTickWidth,
  getDefaultHashedColorsChartConfig,
} from "@/lib/charts";
import { Spinner } from "@/components/ui/spinner";
import useProjectMetric, {
  INTERVAL_TYPE,
  METRIC_NAME_TYPE,
} from "@/api/projects/useProjectMetric";

interface MetricChartProps {
  name: string;
  projectId: string;
  interval: INTERVAL_TYPE;
  intervalStart: string;
  intervalEnd: string;
  disableLoadingData: boolean;
  metricName: METRIC_NAME_TYPE;
}

const tickFormatter = (val: string) => {
  return dayjs(val).utc().format("MM/DD");
};

type TransformedData = { [key: string]: null | number | string };

const MetricChart = ({
  name,
  metricName,
  projectId,
  interval,
  intervalStart,
  intervalEnd,
  disableLoadingData,
}: MetricChartProps) => {
  const { data: traces, isPending } = useProjectMetric(
    {
      projectId,
      metricName,
      interval,
      intervalStart,
      intervalEnd,
    },
    {
      enabled: !!projectId && !disableLoadingData,
      refetchInterval: 30000,
    },
  );

  const [data, lines, values] = useMemo(() => {
    if (!traces?.length) {
      return [[], [], []];
    }

    const lines: string[] = [];
    const values: ProjectMetricValue[] = [];
    const timeValues = traces[0].data?.map((entry) => entry.time);
    const transformedData: TransformedData[] = timeValues.map((time) => ({
      time,
    }));

    traces.forEach((trace) => {
      lines.push(trace.name);

      trace.data.forEach((d, dataIndex) => {
        values.push(d.value);
        transformedData[dataIndex][trace.name] = d.value;
      });
    });

    return [transformedData, lines, values];
  }, [traces]);

  const config = useMemo(() => {
    return getDefaultHashedColorsChartConfig(lines);
  }, [lines]);

  const yTickWidth = useMemo(() => {
    return getDefaultChartYTickWidth({
      values,
    });
  }, [values]);

  const renderContent = () => {
    if (isPending) {
      return (
        <div className="flex h-[var(--chart-height)] w-full  items-center justify-center">
          <Spinner />
        </div>
      );
    }

    return (
      <ChartContainer
        config={config}
        className="h-[var(--chart-height)] w-full"
      >
        <LineChart
          data={data}
          margin={{
            top: 5,
            right: 10,
            left: 0,
            bottom: 5,
          }}
        >
          <CartesianGrid vertical={false} />
          <XAxis
            dataKey="time"
            axisLine={false}
            tickLine={false}
            tick={DEFAULT_CHART_TICK}
            tickFormatter={tickFormatter}
          />
          <YAxis
            tick={DEFAULT_CHART_TICK}
            axisLine={false}
            width={yTickWidth}
            tickLine={false}
          />
          <ChartTooltip
            cursor={false}
            isAnimationActive={false}
            content={<MetricChartTooltipContent />}
          />
          <Tooltip />

          {lines.map((line) => (
            <Line
              key={line}
              type="bump"
              dataKey={line}
              stroke={config[line].color || ""}
              isAnimationActive={false}
              dot={{ strokeWidth: 1, r: 1 }}
              activeDot={{ strokeWidth: 1, r: 3 }}
            />
          ))}
        </LineChart>
      </ChartContainer>
    );
  };

  return (
    <Card>
      <CardHeader className="mb-2">
        <CardTitle>{name}</CardTitle>
      </CardHeader>
      <CardContent>{renderContent()}</CardContent>
    </Card>
  );
};

export default MetricChart;
