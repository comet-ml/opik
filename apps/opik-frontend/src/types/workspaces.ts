export interface WorkspaceMetricSummary {
  name: string;
  current: number | null;
  previous: number | null;
}

export interface WorkspaceMetricData {
  time: string;
  value: number;
}

export interface WorkspaceMetric {
  project_id: string | null;
  name: string;
  data: WorkspaceMetricData[];
}
