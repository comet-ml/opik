import React from 'react';
import { TrendingUp, TrendingDown, Minus } from 'lucide-react';
import BaseWidget from './BaseWidget';
import { BaseWidgetProps, KPIData } from '@/types/widget';
import { formatNumber } from '@/utils/chartHelpers';

interface KPICardProps extends BaseWidgetProps {
  data: KPIData;
}

const KPICard: React.FC<KPICardProps> = ({
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

  const getTrendIcon = () => {
    if (!data.trend) return null;
    
    switch (data.trend.direction) {
      case 'up':
        return <TrendingUp className="h-4 w-4 text-green-600" />;
      case 'down':
        return <TrendingDown className="h-4 w-4 text-red-600" />;
      default:
        return <Minus className="h-4 w-4 text-gray-600" />;
    }
  };

  const getTrendColor = () => {
    if (!data.trend) return 'text-muted-foreground';
    
    switch (data.trend.direction) {
      case 'up':
        return 'text-green-600';
      case 'down':
        return 'text-red-600';
      default:
        return 'text-gray-600';
    }
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
      <div className="space-y-2">
        {/* Main Value */}
        <div className="text-2xl font-bold text-foreground">
          {formatNumber(data.value, data.format)}
        </div>
        
        {/* Label */}
        {data.label && (
          <div className="text-sm text-muted-foreground">
            {data.label}
          </div>
        )}
        
        {/* Trend */}
        {data.trend && (
          <div className={`flex items-center space-x-1 text-sm ${getTrendColor()}`}>
            {getTrendIcon()}
            <span>
              {data.trend.percentage.toFixed(1)}%
            </span>
            <span className="text-muted-foreground">
              vs last period
            </span>
          </div>
        )}
      </div>
    </BaseWidget>
  );
};

export default KPICard;
