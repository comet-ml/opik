export interface WorkspaceChartData {
  time: string;
  value: number;
}

export interface WorkspaceMetricSummary {
  name: string;
  current: number | null;
  previous: number | null;
}

export interface WorkspaceMetric {
  project_id: string | null;
  name: string;
  data: WorkspaceChartData[];
}

export interface WorkspaceCostSummary {
  current: number | null;
  previous: number | null;
}

export interface WorkspaceCost {
  project_id: string | null;
  data: WorkspaceChartData[];
}

export interface WorkspaceConfig {
  timeout_to_mark_thread_as_inactive: string | null;
  truncation_on_tables: boolean | null;
}
