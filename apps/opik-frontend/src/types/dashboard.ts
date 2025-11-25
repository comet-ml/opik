export interface DashboardWidget {
  id: string;
  type: string;
  title: string;
  subtitle?: string;
  metricType?: string;
  config: Record<string, unknown>;
}

export interface DashboardLayoutItem {
  i: string;
  x: number;
  y: number;
  w: number;
  h: number;
  minW?: number;
  maxW?: number;
  minH?: number;
  maxH?: number;
  static?: boolean;
  moved?: boolean;
}

export type DashboardLayout = DashboardLayoutItem[];

export interface DashboardSection {
  id: string;
  title: string;
  expanded: boolean;
  widgets: DashboardWidget[];
  layout: DashboardLayout;
}

export type DashboardSections = DashboardSection[];

export interface DashboardState {
  version: number;
  sections: DashboardSections;
  lastModified: number;
}

export interface Dashboard {
  id: string;
  name: string;
  description?: string;
  workspace_id: string;
  config: DashboardState;
  created_at: string;
  last_updated_at: string;
  created_by?: string;
}

export interface FilteredWidgetsMap {
  [sectionId: string]:
    | {
        [widgetId: string]: boolean;
      }
    | undefined;
}

export interface BaseDashboardConfig {
  [key: string]: unknown;
}

export interface ProjectDashboardConfig extends BaseDashboardConfig {
  projectId: string;
  interval: string;
  intervalStart?: string;
  intervalEnd?: string;
}

export interface DashboardWidgetComponentProps {
  sectionId: string;
  widgetId: string;
}
