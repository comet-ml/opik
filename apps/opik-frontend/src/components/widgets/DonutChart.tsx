import React from 'react';
import {
  PieChart as RechartsPieChart,
  Pie,
  Cell,
  Tooltip,
  Legend,
  ResponsiveContainer,
} from 'recharts';
import BaseWidget from './BaseWidget';
import { BaseWidgetProps, CategoricalData, ChartConfig } from '@/types/widget';
import { formatNumber } from '@/utils/chartHelpers';

interface DonutChartProps extends BaseWidgetProps {
  data: CategoricalData[];
  config?: ChartConfig;
}

const DonutChart: React.FC<DonutChartProps> = ({
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
    showLegend: true,
    height: 300,
    ...config,
  };

  const processedData = React.useMemo(() => {
    if (!data || data.length === 0) return [];

    const total = data.reduce((sum, item) => sum + item.count, 0);
    return data.map(item => ({
      ...item,
      name: item.category,
      value: item.count,
      percentage: ((item.count / total) * 100).toFixed(1),
    }));
  }, [data]);

  const renderTooltip = (props: any) => {
    if (!props.active || !props.payload || props.payload.length === 0) return null;

    const data = props.payload[0].payload;
    return (
      <div className="bg-background border border-border rounded-lg shadow-lg p-3">
        <p className="font-medium">{data.name}</p>
        <p style={{ color: props.payload[0].color }}>
          Count: {formatNumber(data.value)}
        </p>
        <p className="text-muted-foreground">
          Percentage: {data.percentage}%
        </p>
      </div>
    );
  };

  const renderLabel = (entry: any) => {
    return `${entry.percentage}%`;
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
          <RechartsPieChart>
            <Pie
              data={processedData}
              dataKey="value"
              nameKey="name"
              cx="50%"
              cy="50%"
              labelLine={false}
              label={renderLabel}
              outerRadius={80}
              innerRadius={40}
              fill="#8884d8"
            >
              {processedData.map((_, index) => (
                <Cell 
                  key={`cell-${index}`} 
                  fill={chartConfig.colors![index % chartConfig.colors!.length]} 
                />
              ))}
            </Pie>
            <Tooltip content={renderTooltip} />
            {chartConfig.showLegend && <Legend />}
          </RechartsPieChart>
        </ResponsiveContainer>
      )}
    </BaseWidget>
  );
};

export default DonutChart; 