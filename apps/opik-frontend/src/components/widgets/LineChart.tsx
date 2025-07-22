import React from 'react';
import {
  LineChart as RechartsLineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
  ResponsiveContainer,
} from 'recharts';
import BaseWidget from './BaseWidget';
import { BaseWidgetProps, TimeSeriesData, ChartConfig } from '@/types/widget';
import { formatNumber, formatTimestamp } from '@/utils/chartHelpers';

interface LineChartProps extends BaseWidgetProps {
  data: TimeSeriesData[];
  config?: ChartConfig;
}

const LineChart: React.FC<LineChartProps> = ({
  id,
  title,
  loading,
  error,
  data,
  config,
  onEdit,
  onDelete,
  onRefresh,
}) => {
  const chartConfig = {
    colors: ['#8884d8', '#82ca9d', '#ffc658'],
    showLegend: true,
    showGrid: true,
    height: 300,
    ...config,
  };

  // Group data by series
  const groupedData = React.useMemo(() => {
    if (!data || data.length === 0) return [];

    const dataMap = new Map<string, any>();
    
    data.forEach(item => {
      const timestamp = formatTimestamp(item.timestamp, 'short');
      if (!dataMap.has(timestamp)) {
        dataMap.set(timestamp, { timestamp });
      }
      dataMap.get(timestamp)![item.series] = item.value;
    });

    return Array.from(dataMap.values()).sort((a, b) => 
      new Date(a.timestamp).getTime() - new Date(b.timestamp).getTime()
    );
  }, [data]);

  // Get unique series names
  const seriesNames = React.useMemo(() => {
    if (!data || data.length === 0) return [];
    return [...new Set(data.map(item => item.series))];
  }, [data]);

  const renderTooltip = (props: any) => {
    if (!props.active || !props.payload || !props.label) return null;

    return (
      <div className="bg-background border border-border rounded-lg shadow-lg p-3">
        <p className="font-medium">{props.label}</p>
        {props.payload.map((entry: any, index: number) => (
          <p key={index} style={{ color: entry.color }}>
            {entry.dataKey}: {formatNumber(entry.value)}
          </p>
        ))}
      </div>
    );
  };

  return (
    <BaseWidget
      id={id}
      title={title}
      loading={loading}
      error={error}
      onEdit={onEdit}
      onDelete={onDelete}
      onRefresh={onRefresh}
      className="group"
    >
      {groupedData.length === 0 ? (
        <div className="flex items-center justify-center h-[300px] text-muted-foreground">
          No data available
        </div>
      ) : (
        <ResponsiveContainer width="100%" height={chartConfig.height}>
          <RechartsLineChart
            data={groupedData}
            margin={{ top: 5, right: 30, left: 20, bottom: 5 }}
          >
            {chartConfig.showGrid && (
              <CartesianGrid strokeDasharray="3 3" className="opacity-30" />
            )}
            <XAxis 
              dataKey="timestamp" 
              tick={{ fontSize: 12 }}
              axisLine={false}
              tickLine={false}
            />
            <YAxis 
              tick={{ fontSize: 12 }}
              axisLine={false}
              tickLine={false}
              tickFormatter={(value) => formatNumber(value)}
            />
            <Tooltip content={renderTooltip} />
            {chartConfig.showLegend && <Legend />}
            {seriesNames.map((series, index) => (
              <Line
                key={series}
                type="monotone"
                dataKey={series}
                stroke={chartConfig.colors![index % chartConfig.colors!.length]}
                strokeWidth={2}
                dot={{ r: 4 }}
                activeDot={{ r: 6 }}
              />
            ))}
          </RechartsLineChart>
        </ResponsiveContainer>
      )}
    </BaseWidget>
  );
};

export default LineChart;
