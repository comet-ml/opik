import React from 'react';
import BaseWidget from './BaseWidget';
import { BaseWidgetProps, KPIData } from '@/types/widget';
import { formatNumber } from '@/utils/chartHelpers';

interface ProgressBarProps extends BaseWidgetProps {
  data: KPIData;
}

const ProgressBar: React.FC<ProgressBarProps> = ({
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
  
  const getProgressColor = () => {
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
      <div className="space-y-3">
        {/* Progress Bar */}
        <div className="w-full bg-gray-200 rounded-full h-3">
          <div
            className="h-3 rounded-full transition-all duration-300"
            style={{
              width: `${percentage}%`,
              backgroundColor: getProgressColor(),
            }}
          />
        </div>
        
        {/* Value and Label */}
        <div className="flex justify-between items-center">
          <div className="text-2xl font-bold text-foreground">
            {formatNumber(data.value, data.format)}
          </div>
          <div className="text-sm text-muted-foreground">
            {percentage.toFixed(1)}%
          </div>
        </div>
        
        {/* Label */}
        {data.label && (
          <div className="text-sm text-muted-foreground">
            {data.label}
          </div>
        )}
      </div>
    </BaseWidget>
  );
};

export default ProgressBar; 