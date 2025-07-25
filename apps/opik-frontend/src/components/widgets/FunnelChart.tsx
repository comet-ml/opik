import React from 'react';
import {
  BarChart as RechartsBarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  Cell,
} from 'recharts';
import BaseWidget from './BaseWidget';
import { BaseWidgetProps, CategoricalData, ChartConfig } from '@/types/widget';
import { formatNumber } from '@/utils/chartHelpers';

interface FunnelChartProps extends BaseWidgetProps {
  data: CategoricalData[];
  config?: ChartConfig;
}

const FunnelChart: React.FC<FunnelChartProps> = ({
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
    colors: ['#8884d8', '#82ca9d', '#ffc658', '#ff7300', '#00ff00'],
    showLegend: false,
    showGrid: false,
    height: 300,
    ...config,
  };

  const processedData = React.useMemo(() => {
    if (!data || data.length === 0) return [];

    // Sort data by count (highest to lowest) to create funnel effect
    const sortedData = [...data].sort((a, b) => b.count - a.count);
    const maxValue = sortedData[0]?.count || 1;

    return sortedData.map((item, index) => ({
      ...item,
      category: item.category,
      count: item.count,
      percentage: ((item.count / maxValue) * 100).toFixed(1),
      conversionRate: index > 0 ? ((item.count / sortedData[index - 1].count) * 100).toFixed(1) : '100.0',
      barWidth: Math.max((item.count / maxValue) * 100, 10), // Minimum 10% width for visibility
    }));
  }, [data]);

  const renderTooltip = (props: any) => {
    if (!props.active || !props.payload || !props.label) return null;

    const data = props.payload[0]?.payload;
    if (!data) return null;

    return (
      <div className="bg-background border border-border rounded-lg shadow-lg p-3">
        <p className="font-medium mb-2">{props.label}</p>
        <div className="space-y-1 text-sm">
          <p className="flex justify-between">
            <span>Count:</span>
            <span className="font-medium">{formatNumber(data.count)}</span>
          </p>
          <p className="flex justify-between">
            <span>Conversion Rate:</span>
            <span className="font-medium">{data.conversionRate}%</span>
          </p>
          <p className="flex justify-between">
            <span>% of Total:</span>
            <span className="font-medium">{data.percentage}%</span>
          </p>
        </div>
      </div>
    );
  };

  const renderChart = () => {
    if (!processedData.length) {
      return (
        <div className="flex items-center justify-center h-full text-muted-foreground">
          No data available
        </div>
      );
    }

    return (
      <ResponsiveContainer width="100%" height={chartConfig.height}>
        <RechartsBarChart
          data={processedData}
          layout="horizontal"
          margin={{ top: 20, right: 30, left: 20, bottom: 5 }}
        >
          {chartConfig.showGrid && <CartesianGrid strokeDasharray="3 3" opacity={0.3} />}
          <XAxis 
            type="number" 
            domain={[0, 'dataMax']}
            tickFormatter={(value) => formatNumber(value)}
          />
          <YAxis 
            type="category" 
            dataKey="category" 
            width={100}
            tick={{ fontSize: 12 }}
          />
          <Tooltip content={renderTooltip} />
          <Bar 
            dataKey="count"
            radius={[0, 4, 4, 0]}
          >
            {processedData.map((entry, index) => (
              <Cell 
                key={`cell-${index}`} 
                fill={chartConfig.colors?.[index % chartConfig.colors.length] || '#8884d8'}
                opacity={0.8}
              />
            ))}
          </Bar>
        </RechartsBarChart>
      </ResponsiveContainer>
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
    >
      {renderChart()}
      {processedData.length > 0 && (
        <div className="mt-4 px-4 pb-4">
          <div className="text-xs text-muted-foreground space-y-1">
            <p>Total stages: {processedData.length}</p>
            <p>Overall conversion: {((processedData[processedData.length - 1]?.count || 0) / (processedData[0]?.count || 1) * 100).toFixed(1)}%</p>
          </div>
        </div>
      )}
    </BaseWidget>
  );
};

export default FunnelChart; 