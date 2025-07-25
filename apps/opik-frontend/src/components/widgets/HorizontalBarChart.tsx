import React from 'react';
import {
  BarChart as RechartsBarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
  ResponsiveContainer,
} from 'recharts';
import BaseWidget from './BaseWidget';
import { BaseWidgetProps, CategoricalData, ChartConfig } from '@/types/widget';
import { formatNumber } from '@/utils/chartHelpers';

interface HorizontalBarChartProps extends BaseWidgetProps {
  data: CategoricalData[];
  config?: ChartConfig;
}

const HorizontalBarChart: React.FC<HorizontalBarChartProps> = ({
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
    colors: ['#8884d8'],
    showLegend: false,
    showGrid: true,
    height: 300,
    ...config,
  };

  const renderTooltip = (props: any) => {
    if (!props.active || !props.payload || !props.label) return null;

    return (
      <div className="bg-background border border-border rounded-lg shadow-lg p-3">
        <p className="font-medium">{props.label}</p>
        {props.payload.map((entry: any, index: number) => (
          <p key={index} style={{ color: entry.color }}>
            Count: {formatNumber(entry.value)}
            {entry.payload.percentage && (
              <span className="text-muted-foreground ml-2">
                ({entry.payload.percentage.toFixed(1)}%)
              </span>
            )}
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
      {!data || data.length === 0 ? (
        <div className="flex items-center justify-center h-[300px] text-muted-foreground">
          No data available
        </div>
      ) : (
        <ResponsiveContainer width="100%" height={chartConfig.height}>
          <RechartsBarChart
            layout="horizontal"
            data={data}
            margin={{ top: 5, right: 30, left: 40, bottom: 5 }}
          >
            {chartConfig.showGrid && (
              <CartesianGrid strokeDasharray="3 3" className="opacity-30" />
            )}
            <XAxis 
              type="number"
              tick={{ fontSize: 12 }}
              axisLine={false}
              tickLine={false}
              tickFormatter={(value) => formatNumber(value)}
            />
            <YAxis 
              type="category"
              dataKey="category"
              tick={{ fontSize: 12 }}
              axisLine={false}
              tickLine={false}
            />
            <Tooltip content={renderTooltip} />
            {chartConfig.showLegend && <Legend />}
            <Bar 
              dataKey="count" 
              fill={chartConfig.colors![0]}
              radius={[0, 4, 4, 0]}
            />
          </RechartsBarChart>
        </ResponsiveContainer>
      )}
    </BaseWidget>
  );
};

export default HorizontalBarChart; 