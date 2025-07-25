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
  filters: Record<string, any>; // Changed from nested structure to flat
  refreshInterval: number; // seconds
  createdAt?: string; // Updated field name to match backend
  createdBy?: string; // Added missing field
  lastUpdatedAt?: string; // Updated field name to match backend  
  lastUpdatedBy?: string; // Added missing field
  // Keep legacy fields for compatibility
  created?: string; // ISO date
  modified?: string; // ISO date
}

export interface WidgetConfig {
  title: string;
  dataSource?: string; // API endpoint - made optional to match backend
  queryParams?: Record<string, any>; // Dynamic query parameters
  chartOptions?: Record<string, any>; // Chart-specific configuration
  refreshInterval?: number; // Widget-specific refresh interval
}

export type WidgetType = 
  | "line_chart" 
  | "bar_chart" 
  | "pie_chart" 
  | "table" 
  | "kpi_card" 
  | "heatmap"
  | "area_chart"
  | "donut_chart"
  | "scatter_plot"
  | "gauge_chart"
  | "progress_bar"
  | "number_card"
  | "funnel_chart"
  | "horizontal_bar_chart";

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
  layout?: {
    grid: DashboardLayout[];
  };
  filters?: Record<string, any>; // Updated to match backend
  refreshInterval?: number;
}
