import React from 'react';
import {
  ScatterChart as RechartsScatterChart,
  Scatter,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
} from 'recharts';
import BaseWidget from './BaseWidget';
import { BaseWidgetProps, CategoricalData, ChartConfig } from '@/types/widget';
import { formatNumber } from '@/utils/chartHelpers';

interface ScatterPlotProps extends BaseWidgetProps {
  data: CategoricalData[];
  config?: ChartConfig;
}

const ScatterPlot: React.FC<ScatterPlotProps> = ({
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

  const processedData = React.useMemo(() => {
    if (!data || data.length === 0) return [];
    
    return data.map((item, index) => ({
      x: index + 1,
      y: item.count,
      name: item.category,
    }));
  }, [data]);

  const renderTooltip = (props: any) => {
    if (!props.active || !props.payload || props.payload.length === 0) return null;

    const data = props.payload[0].payload;
    return (
      <div className="bg-background border border-border rounded-lg shadow-lg p-3">
        <p className="font-medium">{data.name}</p>
        <p style={{ color: props.payload[0].color }}>
          Value: {formatNumber(data.y)}
        </p>
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
      {processedData.length === 0 ? (
        <div className="flex items-center justify-center h-[300px] text-muted-foreground">
          No data available
        </div>
      ) : (
        <ResponsiveContainer width="100%" height={chartConfig.height}>
          <RechartsScatterChart
            data={processedData}
            margin={{ top: 5, right: 30, left: 20, bottom: 5 }}
          >
            {chartConfig.showGrid && (
              <CartesianGrid strokeDasharray="3 3" className="opacity-30" />
            )}
            <XAxis 
              dataKey="x"
              type="number"
              tick={{ fontSize: 12 }}
              axisLine={false}
              tickLine={false}
              tickFormatter={(value) => formatNumber(value)}
            />
            <YAxis 
              dataKey="y"
              type="number"
              tick={{ fontSize: 12 }}
              axisLine={false}
              tickLine={false}
              tickFormatter={(value) => formatNumber(value)}
            />
            <Tooltip content={renderTooltip} />
            <Scatter
              data={processedData}
              fill={chartConfig.colors![0]}
            />
          </RechartsScatterChart>
        </ResponsiveContainer>
      )}
    </BaseWidget>
  );
};

export default ScatterPlot; 