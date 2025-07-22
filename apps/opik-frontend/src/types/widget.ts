import { WidgetType, WidgetConfig, GridPosition } from './dashboard';

export interface DataSource {
  endpoint: string;
  method: 'GET' | 'POST';
  headers?: Record<string, string>;
  queryParams?: Record<string, any>;
  transformData?: (data: any) => any;
  
  // Data extraction configuration
  dataPath?: string; // Path to extract data from response (e.g., "data", "content", "results")
  totalPath?: string; // Path to total count (e.g., "total", "count", "pagination.total")
}

// Data preview and field analysis types
export interface ApiConnectionTest {
  status: 'idle' | 'loading' | 'success' | 'error';
  message: string;
  lastTested?: Date;
}

export interface ExtractedField {
  name: string;
  path: string;
  type: 'string' | 'number' | 'integer' | 'date' | 'boolean' | 'url' | 'unknown';
  sampleValue?: any;
  isArray?: boolean;
  isNested?: boolean;
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
  valueKey?: string; // For KPI cards and single-value widgets
  labelKey?: string; // For pie charts and labeled widgets
  showLegend?: boolean;
  showGrid?: boolean;
  responsive?: boolean;
  height?: number;
  width?: number;
  
  // Enhanced field mapping
  fieldMapping?: {
    xAxis?: FieldMapping;
    yAxis?: FieldMapping;
    value?: FieldMapping;
    label?: FieldMapping;
  };
}

export interface FieldMapping {
  path: string; // JSON path to the field (e.g., "data.total", "content[].name")
  type: 'string' | 'number' | 'integer' | 'date' | 'boolean' | 'url' | 'unknown';
  transform?: 'count' | 'sum' | 'avg' | 'min' | 'max' | 'none';
  format?: string; // For date formatting, number formatting, etc.
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
