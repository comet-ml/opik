// Dashboard types for experiment dashboard (Python panels)
// These types align with the backend API structure

export interface Dashboard {
  id: string;
  name: string;
  description?: string;
  created_at: string;
  created_by: string;
  last_updated_at: string;
  last_updated_by: string;
}

export interface DashboardWithSections extends Dashboard {
  sections: DashboardSection[];
}

export interface DashboardSection {
  id: string;
  title: string;
  position_order: number;
  created_at: string;
  created_by: string;
  last_updated_at: string;
  last_updated_by: string;
  panels: DashboardPanel[];
}

export interface DashboardPanel {
  id: string;
  name: string;
  type: 'PYTHON' | 'CHART' | 'TEXT' | 'METRIC' | 'HTML';
  configuration: any;
  layout: PanelLayoutItem;
  template_id?: string;
  created_at: string;
  created_by: string;
  last_updated_at: string;
  last_updated_by: string;
}

export interface ExperimentDashboard {
  experiment_id: string;
  dashboard_id: string;
  workspace_id: string;
  created_at: string;
  created_by: string;
  last_updated_at: string;
  last_updated_by: string;
}

// Frontend-specific types for the UI
export type PanelSectionLayout = PanelLayoutItem[];

export type PanelLayoutItem = {
  i: string;
  x: number;
  y: number;
  h: number;
  w: number;
};

export type Panel = {
  id: string;
  name: string;
  data: PythonPanel | ChartPanel | TextPanel | MetricPanel | HtmlPanel;
};

export type PythonPanel = {
  type: 'python';
  config: PythonPanelConfig;
};

export type PythonPanelConfig = {
  code: string;
};

export type ChartPanel = {
  type: 'chart';
  config: ChartPanelConfig;
};

export type ChartPanelConfig = {
  chartType: 'line' | 'bar' | 'scatter' | 'histogram';
  dataSource: string;
  xAxis: string;
  yAxis: string;
  title?: string;
};

export type TextPanel = {
  type: 'text';
  config: TextPanelConfig;
};

export type TextPanelConfig = {
  content: string;
  format: 'markdown' | 'html' | 'plain';
};

export type MetricPanel = {
  type: 'metric';
  config: MetricPanelConfig;
};

export type MetricPanelConfig = {
  metricName: string;
  aggregation: 'sum' | 'avg' | 'count' | 'min' | 'max';
  timeRange: string;
  displayFormat: 'number' | 'percentage' | 'currency';
};

export type HtmlPanel = {
  type: 'html';
  config: HtmlPanelConfig;
};

export type HtmlPanelConfig = {
  htmlContent: string;
  allowScripts: boolean;
  height: number;
  cssIncludes: string[];
  jsIncludes: string[];
};

// UI State types
export type PanelSection = {
  id: string;
  isExpanded: boolean;
  items: Panel[];
  layout: PanelSectionLayout;
  title: string;
};

// Legacy types for backward compatibility (will be removed)
export type ModelView = {
  id: string;
  experiment_id: string;
  is_default: boolean;
  name: string;
  data: ModelViewData;
  version: number;
  created_at: string;
  updated_at: string;
  last_updated_by: string;
};

export type ModelViewData = {
  sections: PanelSection[];
};

export type ModelViewSummary = Omit<ModelView, 'data'>;
export type UpsertModelView = Partial<Pick<ModelView, 'name' | 'data' | 'version'>>;

// Dashboard Management Types (for UI only)
export type DashboardMetadata = {
  id: string;
  name: string;
  description?: string;
  created_at: string;
  updated_at: string;
  is_template: boolean;
  tags: string[];
};

export type DashboardLibrary = {
  dashboards: DashboardMetadata[];
  selectedDashboardId: string | null;
};

export type ExperimentDashboardSelection = {
  [experimentId: string]: string; // experimentId -> dashboardId
}; 
