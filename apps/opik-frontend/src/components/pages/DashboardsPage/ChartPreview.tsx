import React, { useMemo } from "react";
import {
  LineChart,
  Line,
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
  ResponsiveContainer,
} from "recharts";
import { ChartDataResponse, ChartType, DataSeries } from "@/types/dashboards";
import { format } from "date-fns";

interface ChartPreviewProps {
  data: ChartDataResponse;
  chartType: ChartType;
  dataSeries: DataSeries[]; // Add this to get colors from configuration
  isLoading?: boolean;
}

const ChartPreview: React.FC<ChartPreviewProps> = ({
  data,
  chartType,
  dataSeries,
  isLoading = false,
}) => {
  // Transform backend data to Recharts format
  const chartData = useMemo(() => {
    if (!data.series || data.series.length === 0) {
      return [];
    }

    // Create a map of timestamp -> data point for all series
    const timestampMap = new Map<string, Record<string, number | string>>();

    data.series.forEach((series) => {
      series.data?.forEach((point) => {
        const timestamp = point.time;
        if (!timestampMap.has(timestamp)) {
          timestampMap.set(timestamp, { time: timestamp });
        }
        const dataPoint = timestampMap.get(timestamp)!;
        dataPoint[series.name || "Unknown"] = point.value ?? 0;
      });
    });

    // Convert map to array and sort by timestamp
    return Array.from(timestampMap.values()).sort(
      (a, b) =>
        new Date(a.time as string).getTime() -
        new Date(b.time as string).getTime()
    );
  }, [data.series]);

  // Format timestamp for display
  const formatXAxis = (timestamp: string) => {
    try {
      return format(new Date(timestamp), "MMM dd");
    } catch {
      return timestamp;
    }
  };

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

  const seriesNames = data.series.map((s) => s.name || "Unknown");

  return (
    <div className="h-full w-full">
      <ResponsiveContainer width="100%" height="100%">
        {chartType === "line" ? (
          <LineChart data={chartData}>
            <CartesianGrid strokeDasharray="3 3" />
            <XAxis
              dataKey="time"
              tickFormatter={formatXAxis}
              style={{ fontSize: "12px" }}
            />
            <YAxis style={{ fontSize: "12px" }} />
            <Tooltip
              labelFormatter={(label) => format(new Date(label), "PPP")}
              contentStyle={{
                backgroundColor: "white",
                border: "1px solid #ccc",
                borderRadius: "4px",
              }}
            />
            <Legend />
            {seriesNames.map((name, index) => {
              const seriesConfig = dataSeries[index];
              return (
                <Line
                  key={name}
                  type="monotone"
                  dataKey={name}
                  stroke={seriesConfig?.color || `hsl(${index * 60}, 70%, 50%)`}
                  strokeWidth={2}
                  dot={{ r: 4 }}
                  activeDot={{ r: 6 }}
                />
              );
            })}
          </LineChart>
        ) : (
          <BarChart data={chartData}>
            <CartesianGrid strokeDasharray="3 3" />
            <XAxis
              dataKey="time"
              tickFormatter={formatXAxis}
              style={{ fontSize: "12px" }}
            />
            <YAxis style={{ fontSize: "12px" }} />
            <Tooltip
              labelFormatter={(label) => format(new Date(label), "PPP")}
              contentStyle={{
                backgroundColor: "white",
                border: "1px solid #ccc",
                borderRadius: "4px",
              }}
            />
            <Legend />
            {seriesNames.map((name, index) => {
              const seriesConfig = dataSeries[index];
              return (
                <Bar
                  key={name}
                  dataKey={name}
                  fill={seriesConfig?.color || `hsl(${index * 60}, 70%, 50%)`}
                />
              );
            })}
          </BarChart>
        )}
      </ResponsiveContainer>
    </div>
  );
};

export default ChartPreview;

