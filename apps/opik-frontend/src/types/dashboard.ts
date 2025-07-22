export interface DashboardLayout {
  id: string;
  x: number;
  y: number;
  w: number;
  h: number;
  type: WidgetType;
  config: WidgetConfig;
}

export interface Dashboard {
  id: string;
  name: string;
  description: string;
  layout: {
    grid: DashboardLayout[];
  };
  filters: {
    global: Record<string, any>;
  };
  refreshInterval: number; // seconds
  created: string; // ISO date
  modified: string; // ISO date
}

export interface WidgetConfig {
  title: string;
  dataSource: string; // API endpoint
  queryParams: Record<string, any>; // Dynamic query parameters
  chartOptions: Record<string, any>; // Chart-specific configuration
  refreshInterval?: number; // Widget-specific refresh interval
}

export type WidgetType = 
  | "line_chart" 
  | "bar_chart" 
  | "pie_chart" 
  | "table" 
  | "kpi_card" 
  | "heatmap";

export interface GridPosition {
  x: number;
  y: number;
  w: number;
  h: number;
}

export interface DashboardFilter {
  id: string;
  name: string;
  type: "date_range" | "select" | "multi_select" | "text";
  value: any;
  options?: Array<{ label: string; value: any }>;
}

export interface CreateDashboardRequest {
  name: string;
  description?: string;
}

export interface UpdateDashboardRequest {
  name?: string;
  description?: string;
  layout?: Dashboard['layout'];
  filters?: Dashboard['filters'];
  refreshInterval?: number;
}
