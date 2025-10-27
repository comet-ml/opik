import React, { useCallback, useMemo, useState } from "react";
import {
  Area,
  Line,
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  ComposedChart,
  PieChart,
  Pie,
  Cell,
  AreaChart,
} from "recharts";
import { ChartDataResponse, ChartType, DataSeries } from "@/types/dashboards";
import { format } from "date-fns";
import dayjs from "dayjs";
import snakeCase from "lodash/snakeCase";
import {
  ChartConfig,
  ChartContainer,
  ChartLegend,
  ChartTooltip,
} from "@/components/ui/chart";
import {
  DEFAULT_CHART_GRID_PROPS,
  DEFAULT_CHART_TICK,
} from "@/constants/chart";
import ChartTooltipContent, {
  ChartTooltipRenderHeaderArguments,
} from "@/components/shared/ChartTooltipContent/ChartTooltipContent";
import ChartHorizontalLegendContent from "@/components/shared/ChartHorizontalLegendContent/ChartHorizontalLegendContent";
import useChartTickDefaultConfig from "@/hooks/charts/useChartTickDefaultConfig";
import { TAG_VARIANTS_COLOR_MAP } from "@/components/ui/tag";

interface ChartPreviewProps {
  data: ChartDataResponse;
  chartType: ChartType;
  dataSeries: DataSeries[];
  interval?: "HOURLY" | "DAILY" | "WEEKLY";
  isLoading?: boolean;
}

const ChartPreview: React.FC<ChartPreviewProps> = ({
  data,
  chartType,
  dataSeries,
  interval = "DAILY",
  isLoading = false,
}) => {
  const [activeLine, setActiveLine] = useState<string | null>(null);

  // Transform backend data to Recharts format
  const { chartData, seriesNames, values } = useMemo(() => {
    if (!data.series || data.series.length === 0) {
      return { chartData: [], seriesNames: [], values: [] };
    }

    // Create a map of timestamp -> data point for all series
    const timestampMap = new Map<string, Record<string, number | string>>();
    const values: number[] = [];

    data.series.forEach((series) => {
      series.data?.forEach((point) => {
        const timestamp = point.time;
        if (!timestampMap.has(timestamp)) {
          timestampMap.set(timestamp, { time: timestamp });
        }
        const dataPoint = timestampMap.get(timestamp)!;
        const value = point.value ?? 0;
        dataPoint[series.name || "Unknown"] = value;
        values.push(value as number);
      });
    });

    // Convert map to array and sort by timestamp
    const chartData = Array.from(timestampMap.values()).sort(
      (a, b) =>
        new Date(a.time as string).getTime() -
        new Date(b.time as string).getTime()
    );

    const seriesNames = data.series.map((s) => s.name || "Unknown").sort();

    return { chartData, seriesNames, values };
  }, [data.series]);

  // Create chart config using user-selected colors from dataSeries
  const config: ChartConfig = useMemo(() => {
    const config: ChartConfig = {};
    
    // Map each series name to its configured color
    dataSeries.forEach((series) => {
      const seriesName = series.name || "Unknown";
      config[seriesName] = {
        label: seriesName,
        color: series.color || TAG_VARIANTS_COLOR_MAP.purple, // Use configured color or fallback
      };
    });
    
    return config;
  }, [dataSeries]);

  // Use the same tick configuration as metrics page
  const {
    width: yTickWidth,
    ticks,
    domain,
    interval: yTickInterval,
    yTickFormatter,
  } = useChartTickDefaultConfig(values);

  // Format X-axis based on interval
  const xTickFormatter = useCallback((val: string) => {
    if (interval === "HOURLY") {
      return dayjs(val).utc().format("MM/DD hh:mm A");
    }
    return dayjs(val).utc().format("MM/DD");
  }, [interval]);

  // Render tooltip header
  const renderChartTooltipHeader = useCallback(
    ({ payload }: ChartTooltipRenderHeaderArguments) => {
      return (
        <div className="comet-body-xs mb-1 text-light-slate">
          {format(new Date(payload?.[0]?.payload?.time), "PPP")} UTC
        </div>
      );
    },
    []
  );

  if (isLoading) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="text-muted-foreground">Loading preview...</div>
      </div>
    );
  }

  if (!data.series || data.series.length === 0 || chartData.length === 0) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="text-center">
          <p className="text-lg font-medium text-muted-foreground">
            No data available
          </p>
          <p className="text-sm text-muted-foreground">
            Try adjusting your date range or filters
          </p>
        </div>
      </div>
    );
  }

  const isSingleLine = seriesNames.length === 1;
  const isSinglePoint =
    chartData.filter((point) =>
      seriesNames.every((line) => point[line] !== null)
    ).length === 1;
  const [firstLine] = seriesNames;

  const activeDot = { strokeWidth: 1.5, r: 4, stroke: "white" };

  return (
    <div className="h-full w-full">
      <ChartContainer
        config={config}
        className="h-[var(--chart-height)] w-full"
      >
        {/* Line Chart */}
        {chartType === "line" && (
          <ComposedChart
            data={chartData}
            margin={{ top: 5, right: 10, left: 5, bottom: 5 }}
          >
            <CartesianGrid vertical={false} {...DEFAULT_CHART_GRID_PROPS} />
            <XAxis dataKey="time" axisLine={false} tickLine={false} tick={DEFAULT_CHART_TICK} tickFormatter={xTickFormatter} />
            <YAxis tick={DEFAULT_CHART_TICK} axisLine={false} width={yTickWidth} tickLine={false} tickFormatter={yTickFormatter} ticks={ticks} domain={domain} interval={yTickInterval} />
            <ChartTooltip isAnimationActive={false} content={<ChartTooltipContent renderHeader={renderChartTooltipHeader} />} />
            <ChartLegend content={<ChartHorizontalLegendContent setActiveLine={setActiveLine} chartId="dashboard-chart-preview" />} />
            {isSingleLine ? (
              <>
                <defs>
                  <linearGradient id={`chart-area-gradient-${snakeCase(firstLine)}`} x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor={config[firstLine].color} stopOpacity={0.3}></stop>
                    <stop offset="50%" stopColor={config[firstLine].color} stopOpacity={0}></stop>
                  </linearGradient>
                </defs>
                <Area type="monotone" dataKey={firstLine} stroke={config[firstLine].color} fill={`url(#chart-area-gradient-${snakeCase(firstLine)})`} connectNulls strokeWidth={1.5} activeDot={activeDot} dot={isSinglePoint ? { fill: config[firstLine].color, strokeWidth: 0, fillOpacity: 1 } : false} strokeOpacity={1} />
              </>
            ) : (
              seriesNames.map((line) => {
                const isActive = line === activeLine;
                let strokeOpacity = activeLine ? (isActive ? 1 : 0.4) : 1;
                return <Line key={line} type="linear" dataKey={line} stroke={config[line].color || ""} dot={isSinglePoint ? { fill: config[line].color, strokeWidth: 0 } : false} activeDot={activeDot} connectNulls strokeWidth={1.5} strokeOpacity={strokeOpacity} animationDuration={800} />;
              })
            )}
          </ComposedChart>
        )}

        {/* Bar Chart */}
        {chartType === "bar" && (
          <BarChart data={chartData} margin={{ top: 5, right: 10, left: 5, bottom: 5 }}>
            <CartesianGrid vertical={false} {...DEFAULT_CHART_GRID_PROPS} />
            <XAxis dataKey="time" axisLine={false} tickLine={false} tick={DEFAULT_CHART_TICK} tickFormatter={xTickFormatter} />
            <YAxis tick={DEFAULT_CHART_TICK} axisLine={false} width={yTickWidth} tickLine={false} tickFormatter={yTickFormatter} ticks={ticks} domain={domain} interval={yTickInterval} />
            <ChartTooltip isAnimationActive={false} content={<ChartTooltipContent renderHeader={renderChartTooltipHeader} />} />
            <ChartLegend content={<ChartHorizontalLegendContent setActiveLine={setActiveLine} chartId="dashboard-chart-preview" />} />
            {seriesNames.map((name) => (
              <Bar key={name} dataKey={name} fill={config[name].color || ""} radius={[4, 4, 0, 0]} />
            ))}
          </BarChart>
        )}

        {/* Area Chart */}
        {chartType === "area" && (
          <AreaChart data={chartData} margin={{ top: 5, right: 10, left: 5, bottom: 5 }}>
            <CartesianGrid vertical={false} {...DEFAULT_CHART_GRID_PROPS} />
            <XAxis dataKey="time" axisLine={false} tickLine={false} tick={DEFAULT_CHART_TICK} tickFormatter={xTickFormatter} />
            <YAxis tick={DEFAULT_CHART_TICK} axisLine={false} width={yTickWidth} tickLine={false} tickFormatter={yTickFormatter} ticks={ticks} domain={domain} interval={yTickInterval} />
            <ChartTooltip isAnimationActive={false} content={<ChartTooltipContent renderHeader={renderChartTooltipHeader} />} />
            <ChartLegend content={<ChartHorizontalLegendContent setActiveLine={setActiveLine} chartId="dashboard-chart-preview" />} />
            {seriesNames.map((name) => (
              <React.Fragment key={name}>
                <defs>
                  <linearGradient id={`chart-area-gradient-${snakeCase(name)}`} x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor={config[name].color} stopOpacity={0.3}></stop>
                    <stop offset="95%" stopColor={config[name].color} stopOpacity={0}></stop>
                  </linearGradient>
                </defs>
                <Area type="monotone" dataKey={name} stroke={config[name].color} fill={`url(#chart-area-gradient-${snakeCase(name)})`} strokeWidth={1.5} />
              </React.Fragment>
            ))}
          </AreaChart>
        )}

        {/* Stacked Bar Chart */}
        {chartType === "stacked_bar" && (
          <BarChart data={chartData} margin={{ top: 5, right: 10, left: 5, bottom: 5 }}>
            <CartesianGrid vertical={false} {...DEFAULT_CHART_GRID_PROPS} />
            <XAxis dataKey="time" axisLine={false} tickLine={false} tick={DEFAULT_CHART_TICK} tickFormatter={xTickFormatter} />
            <YAxis tick={DEFAULT_CHART_TICK} axisLine={false} width={yTickWidth} tickLine={false} tickFormatter={yTickFormatter} ticks={ticks} domain={domain} interval={yTickInterval} />
            <ChartTooltip isAnimationActive={false} content={<ChartTooltipContent renderHeader={renderChartTooltipHeader} />} />
            <ChartLegend content={<ChartHorizontalLegendContent setActiveLine={setActiveLine} chartId="dashboard-chart-preview" />} />
            {seriesNames.map((name) => (
              <Bar key={name} dataKey={name} fill={config[name].color || ""} radius={[4, 4, 0, 0]} stackId="stack" />
            ))}
          </BarChart>
        )}

        {/* Stacked Area Chart */}
        {chartType === "stacked_area" && (
          <AreaChart data={chartData} margin={{ top: 5, right: 10, left: 5, bottom: 5 }}>
            <CartesianGrid vertical={false} {...DEFAULT_CHART_GRID_PROPS} />
            <XAxis dataKey="time" axisLine={false} tickLine={false} tick={DEFAULT_CHART_TICK} tickFormatter={xTickFormatter} />
            <YAxis tick={DEFAULT_CHART_TICK} axisLine={false} width={yTickWidth} tickLine={false} tickFormatter={yTickFormatter} ticks={ticks} domain={domain} interval={yTickInterval} />
            <ChartTooltip isAnimationActive={false} content={<ChartTooltipContent renderHeader={renderChartTooltipHeader} />} />
            <ChartLegend content={<ChartHorizontalLegendContent setActiveLine={setActiveLine} chartId="dashboard-chart-preview" />} />
            {seriesNames.map((name) => (
              <Area key={name} type="monotone" dataKey={name} stroke={config[name].color} fill={config[name].color} fillOpacity={0.6} strokeWidth={1.5} stackId="stack" />
            ))}
          </AreaChart>
        )}

        {/* Pie Chart */}
        {(chartType === "pie" || chartType === "donut") && (
          <PieChart>
            <ChartTooltip isAnimationActive={false} content={<ChartTooltipContent />} />
            <ChartLegend content={<ChartHorizontalLegendContent setActiveLine={setActiveLine} chartId="dashboard-chart-preview" />} />
            <Pie
              data={chartData.map((point, idx) => ({
                name: xTickFormatter(point.time),
                value: seriesNames.reduce((sum, name) => sum + (point[name] as number || 0), 0),
                fill: seriesNames.length === 1 ? config[seriesNames[0]].color : `hsl(${(idx * 360) / chartData.length}, 70%, 50%)`,
              }))}
              cx="50%"
              cy="50%"
              labelLine={false}
              label={({ name, percent }) => `${name}: ${(percent * 100).toFixed(0)}%`}
              outerRadius={chartType === "donut" ? 80 : 100}
              innerRadius={chartType === "donut" ? 50 : 0}
              fill="#8884d8"
              dataKey="value"
            >
              {chartData.map((_, index) => (
                <Cell key={`cell-${index}`} />
              ))}
            </Pie>
          </PieChart>
        )}
      </ChartContainer>

      {/* Data Table */}
      {chartData.length > 0 && (
        <div className="mt-6 space-y-4">
          <div className="flex items-center justify-between">
            <h3 className="text-sm font-semibold">Data Points</h3>
            <div className="flex items-center gap-4 text-xs text-muted-foreground">
              <span>Interval: <strong className="text-foreground">{interval}</strong></span>
              <span>Points: <strong className="text-foreground">{chartData.length}</strong></span>
              <span>Series: <strong className="text-foreground">{seriesNames.length}</strong></span>
            </div>
          </div>
          
          <div className="overflow-x-auto rounded-md border">
            <table className="w-full text-sm">
              <thead className="bg-muted/50">
                <tr>
                  <th className="border-b px-4 py-2 text-left font-medium">Time</th>
                  {seriesNames.map((name) => (
                    <th key={name} className="border-b px-4 py-2 text-right font-medium">
                      <div className="flex items-center justify-end gap-2">
                        <div
                          className="h-2 w-2 rounded-full"
                          style={{ backgroundColor: config[name].color }}
                        />
                        {name}
                      </div>
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {chartData.map((point, idx) => (
                  <tr key={idx} className="hover:bg-muted/30">
                    <td className="border-b px-4 py-2 font-medium">
                      {xTickFormatter(point.time)}
                    </td>
                    {seriesNames.map((name) => (
                      <td key={name} className="border-b px-4 py-2 text-right tabular-nums">
                        {point[name] !== undefined && point[name] !== null
                          ? typeof point[name] === 'number'
                            ? point[name].toLocaleString(undefined, {
                                minimumFractionDigits: 0,
                                maximumFractionDigits: 2,
                              })
                            : point[name]
                          : '-'}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
              {/* Summary row */}
              {seriesNames.length > 0 && (
                <tfoot className="bg-muted/50 font-semibold">
                  <tr>
                    <td className="px-4 py-2">Summary</td>
                    {seriesNames.map((name) => {
                      const values = chartData
                        .map((p) => p[name])
                        .filter((v) => v !== undefined && v !== null && typeof v === 'number') as number[];
                      
                      if (values.length === 0) {
                        return <td key={name} className="px-4 py-2 text-right">-</td>;
                      }
                      
                      const sum = values.reduce((a, b) => a + b, 0);
                      const avg = sum / values.length;
                      const min = Math.min(...values);
                      const max = Math.max(...values);
                      
                      return (
                        <td key={name} className="px-4 py-2 text-right text-xs">
                          <div className="space-y-0.5">
                            <div>Avg: {avg.toLocaleString(undefined, { minimumFractionDigits: 0, maximumFractionDigits: 2 })}</div>
                            <div className="text-muted-foreground">
                              Min: {min.toLocaleString(undefined, { maximumFractionDigits: 2 })} | Max: {max.toLocaleString(undefined, { maximumFractionDigits: 2 })}
                            </div>
                          </div>
                        </td>
                      );
                    })}
                  </tr>
                </tfoot>
              )}
            </table>
          </div>
        </div>
      )}
    </div>
  );
};

export default ChartPreview;

