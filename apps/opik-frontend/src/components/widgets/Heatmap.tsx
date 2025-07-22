import React from 'react';
import BaseWidget from './BaseWidget';
import { BaseWidgetProps, HeatmapData, ChartConfig } from '@/types/widget';

interface HeatmapProps extends BaseWidgetProps {
  data: HeatmapData[];
  config?: ChartConfig;
}

const Heatmap: React.FC<HeatmapProps> = ({
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
    colors: ['#f0f9ff', '#0ea5e9'],
    height: 300,
    ...config,
  };

  // Process data for heatmap grid
  const { gridData, xLabels, yLabels, minValue, maxValue } = React.useMemo(() => {
    if (!data || data.length === 0) {
      return { gridData: [], xLabels: [], yLabels: [], minValue: 0, maxValue: 0 };
    }

    const xSet = new Set<string>();
    const ySet = new Set<string>();
    let min = Infinity;
    let max = -Infinity;

    data.forEach(item => {
      xSet.add(String(item.x));
      ySet.add(String(item.y));
      min = Math.min(min, item.value);
      max = Math.max(max, item.value);
    });

    const xLabels = Array.from(xSet).sort();
    const yLabels = Array.from(ySet).sort();

    // Create grid
    const grid = yLabels.map(y => 
      xLabels.map(x => {
        const dataPoint = data.find(d => String(d.x) === x && String(d.y) === y);
        return {
          x,
          y,
          value: dataPoint?.value || 0,
        };
      })
    );

    return {
      gridData: grid,
      xLabels,
      yLabels,
      minValue: min,
      maxValue: max,
    };
  }, [data]);

  const getColor = (value: number) => {
    if (maxValue === minValue) return chartConfig.colors![0];
    
    const intensity = (value - minValue) / (maxValue - minValue);
    const startColor = hexToRgb(chartConfig.colors![0]);
    const endColor = hexToRgb(chartConfig.colors![1]);
    
    if (!startColor || !endColor) return chartConfig.colors![0];
    
    const r = Math.round(startColor.r + (endColor.r - startColor.r) * intensity);
    const g = Math.round(startColor.g + (endColor.g - startColor.g) * intensity);
    const b = Math.round(startColor.b + (endColor.b - startColor.b) * intensity);
    
    return `rgb(${r}, ${g}, ${b})`;
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
      {gridData.length === 0 ? (
        <div className="flex items-center justify-center h-[300px] text-muted-foreground">
          No data available
        </div>
      ) : (
        <div className="space-y-4">
          <div className="overflow-auto">
            <div className="inline-block min-w-full">
              {/* Y-axis labels and grid */}
              <div className="flex">
                {/* Y-axis labels */}
                <div className="flex flex-col items-end pr-2 pt-6">
                  {yLabels.map(label => (
                    <div
                      key={label}
                      className="h-8 flex items-center text-xs text-muted-foreground"
                    >
                      {label}
                    </div>
                  ))}
                </div>
                
                {/* Grid */}
                <div>
                  {/* X-axis labels */}
                  <div className="flex pb-1">
                    {xLabels.map(label => (
                      <div
                        key={label}
                        className="w-8 text-center text-xs text-muted-foreground"
                      >
                        {label}
                      </div>
                    ))}
                  </div>
                  
                  {/* Heatmap cells */}
                  {gridData.map((row, rowIndex) => (
                    <div key={rowIndex} className="flex">
                      {row.map((cell, colIndex) => (
                        <div
                          key={`${rowIndex}-${colIndex}`}
                          className="w-8 h-8 border border-border relative group cursor-pointer"
                          style={{ backgroundColor: getColor(cell.value) }}
                          title={`${cell.x}, ${cell.y}: ${cell.value}`}
                        >
                          {/* Tooltip on hover */}
                          <div className="absolute bottom-full left-1/2 transform -translate-x-1/2 mb-2 px-2 py-1 bg-background border border-border rounded text-xs opacity-0 group-hover:opacity-100 transition-opacity z-10 whitespace-nowrap">
                            {cell.x}, {cell.y}: {cell.value}
                          </div>
                        </div>
                      ))}
                    </div>
                  ))}
                </div>
              </div>
            </div>
          </div>
          
          {/* Legend */}
          <div className="flex items-center justify-between text-xs text-muted-foreground">
            <span>Min: {minValue}</span>
            <div className="flex-1 mx-4 h-4 relative" style={{
              background: `linear-gradient(to right, ${chartConfig.colors![0]}, ${chartConfig.colors![1]})`
            }}>
              <div className="absolute inset-0 border border-border rounded"></div>
            </div>
            <span>Max: {maxValue}</span>
          </div>
        </div>
      )}
    </BaseWidget>
  );
};

// Helper function to convert hex to RGB
function hexToRgb(hex: string) {
  const result = /^#?([a-f\d]{2})([a-f\d]{2})([a-f\d]{2})$/i.exec(hex);
  return result ? {
    r: parseInt(result[1], 16),
    g: parseInt(result[2], 16),
    b: parseInt(result[3], 16),
  } : null;
}

export default Heatmap;
