import React from 'react';
import BaseWidget from './BaseWidget';
import { BaseWidgetProps, KPIData } from '@/types/widget';
import { formatNumber } from '@/utils/chartHelpers';

interface NumberCardProps extends BaseWidgetProps {
  data: KPIData;
}

const NumberCard: React.FC<NumberCardProps> = ({
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
        <div className="text-3xl font-bold text-foreground">
          {formatNumber(data.value, data.format)}
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

export default NumberCard; 