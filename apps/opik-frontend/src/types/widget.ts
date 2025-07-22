import { WidgetType, WidgetConfig, GridPosition } from './dashboard';

export interface DataSource {
  endpoint: string;
  method: 'GET' | 'POST';
  headers?: Record<string, string>;
  queryParams?: Record<string, any>;
  transformData?: (data: any) => any;
}

export interface Widget {
  id: string;
  type: WidgetType;
  title: string;
  dataSource: DataSource;
  chartConfig: ChartConfig;
  position: GridPosition;
}

export interface ChartConfig {
  colors?: string[];
  xAxisKey?: string;
  yAxisKey?: string;
  dataKey?: string;
  showLegend?: boolean;
  showGrid?: boolean;
  responsive?: boolean;
  height?: number;
  width?: number;
}

export interface BaseWidgetProps {
  id: string;
  title: string;
  loading?: boolean;
  error?: string | null;
  data?: any;
  config?: ChartConfig;
  onEdit?: () => void;
  onDelete?: () => void;
  onRefresh?: () => void;
}

export interface TimeSeriesData {
  timestamp: string;
  value: number;
  series: string;
}

export interface CategoricalData {
  category: string;
  count: number;
  percentage?: number;
}

export interface TableData {
  id: string | number;
  [key: string]: any;
}

export interface KPIData {
  value: number;
  label: string;
  trend?: {
    direction: 'up' | 'down' | 'neutral';
    percentage: number;
  };
  format?: 'number' | 'percentage' | 'currency' | 'duration';
}

export interface HeatmapData {
  x: string | number;
  y: string | number;
  value: number;
}

export interface WidgetDataResponse<T = any> {
  data: T[];
  pagination?: {
    page: number;
    totalPages: number;
    totalItems: number;
  };
  metadata?: Record<string, any>;
}
