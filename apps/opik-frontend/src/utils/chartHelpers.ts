import { ChartConfig } from '@/types/widget';

export const defaultChartConfig: ChartConfig = {
  colors: ['#8884d8', '#82ca9d', '#ffc658', '#ff7300', '#00ff00'],
  showLegend: true,
  showGrid: true,
  responsive: true,
  height: 300,
};

export const chartColorPalettes = {
  default: ['#8884d8', '#82ca9d', '#ffc658', '#ff7300', '#00ff00'],
  blue: ['#1f77b4', '#aec7e8', '#17becf', '#9edae5', '#c5dbef'],
  green: ['#2ca02c', '#98df8a', '#8c564b', '#c49c94', '#e377c2'],
  warm: ['#ff7f0e', '#ffbb78', '#d62728', '#ff9896', '#9467bd'],
  cool: ['#17becf', '#9edae5', '#2ca02c', '#98df8a', '#1f77b4'],
};

export function formatNumber(
  value: number,
  format: 'number' | 'percentage' | 'currency' | 'duration' = 'number'
): string {
  switch (format) {
    case 'percentage':
      return `${value.toFixed(1)}%`;
    case 'currency':
      return new Intl.NumberFormat('en-US', {
        style: 'currency',
        currency: 'USD',
      }).format(value);
    case 'duration':
      if (value < 1000) return `${value}ms`;
      if (value < 60000) return `${(value / 1000).toFixed(1)}s`;
      return `${(value / 60000).toFixed(1)}m`;
    default:
      return new Intl.NumberFormat('en-US').format(value);
  }
}

export function formatTimestamp(timestamp: string, format: 'short' | 'long' = 'short'): string {
  const date = new Date(timestamp);
  
  if (format === 'long') {
    return date.toLocaleString();
  }
  
  const now = new Date();
  const diff = now.getTime() - date.getTime();
  
  if (diff < 60000) return 'just now';
  if (diff < 3600000) return `${Math.floor(diff / 60000)}m ago`;
  if (diff < 86400000) return `${Math.floor(diff / 3600000)}h ago`;
  
  return date.toLocaleDateString();
}

export function calculateTrend(current: number, previous: number): {
  direction: 'up' | 'down' | 'neutral';
  percentage: number;
} {
  if (previous === 0) {
    return { direction: 'neutral', percentage: 0 };
  }
  
  const percentage = ((current - previous) / previous) * 100;
  
  return {
    direction: percentage > 0 ? 'up' : percentage < 0 ? 'down' : 'neutral',
    percentage: Math.abs(percentage),
  };
}

export function getResponsiveChartHeight(containerWidth: number): number {
  if (containerWidth < 400) return 200;
  if (containerWidth < 600) return 250;
  if (containerWidth < 800) return 300;
  return 350;
}

export function transformDataForChart(
  data: any[],
  chartType: string,
  config: ChartConfig
): any[] {
  switch (chartType) {
    case 'line_chart':
    case 'bar_chart':
      return data.map(item => ({
        ...item,
        timestamp: formatTimestamp(item.timestamp, 'short'),
      }));
    
    case 'pie_chart':
      return data.map(item => ({
        name: item.category,
        value: item.count,
        percentage: item.percentage,
      }));
    
    default:
      return data;
  }
}
