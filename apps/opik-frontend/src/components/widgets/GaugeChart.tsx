import React from 'react';
import BaseWidget from './BaseWidget';
import { BaseWidgetProps, KPIData } from '@/types/widget';
import { formatNumber } from '@/utils/chartHelpers';

interface GaugeChartProps extends BaseWidgetProps {
  data: KPIData;
}

const GaugeChart: React.FC<GaugeChartProps> = ({
  id,
  title,
  loading,
  error,
  data,
  onEdit,
  onDelete,
  onRefresh,
}) => {
  if (!data) {
    return (
      <BaseWidget
        id={id}
        title={title}
        loading={loading}
        error={error || 'No data available'}
        onEdit={onEdit}
        onDelete={onDelete}
        onRefresh={onRefresh}
      >
        <div className="flex items-center justify-center h-24 text-muted-foreground">
          No data available
        </div>
      </BaseWidget>
    );
  }

  // Assume data.value is a percentage (0-100)
  const percentage = Math.max(0, Math.min(100, data.value));
  const angle = (percentage / 100) * 180; // Convert to degrees for half-circle

  const getColor = () => {
    if (percentage >= 80) return '#22c55e'; // green
    if (percentage >= 60) return '#3b82f6'; // blue
    if (percentage >= 40) return '#f59e0b'; // yellow
    return '#ef4444'; // red
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
      <div className="space-y-4">
        {/* Gauge Visual */}
        <div className="relative h-20 flex items-end justify-center">
          <div className="relative w-32 h-16 overflow-hidden">
            {/* Background arc */}
            <div className="absolute inset-0 border-4 border-gray-200 rounded-t-full"></div>
            {/* Progress arc */}
            <div 
              className="absolute inset-0 border-4 rounded-t-full transition-all duration-500"
              style={{
                borderColor: getColor(),
                transform: `rotate(${angle - 90}deg)`,
                transformOrigin: 'center bottom',
              }}
            ></div>
          </div>
        </div>
        
        {/* Value */}
        <div className="text-center">
          <div className="text-2xl font-bold text-foreground">
            {formatNumber(data.value, data.format)}
          </div>
          {data.label && (
            <div className="text-sm text-muted-foreground">
              {data.label}
            </div>
          )}
        </div>
      </div>
    </BaseWidget>
  );
};

export default GaugeChart; 