export interface Project {
  id: string;
  name: string;
  description: string;
  created_at: string;
  created_by: string;
  last_updated_at: string;
  last_updated_by: string;
  last_updated_trace_at?: string;
}

export type ProjectMetricValue = number | null;

export interface ProjectMetricDataPoint {
  time: string;
  value: ProjectMetricValue;
}

export interface ProjectMetricTrace {
  name: string;
  data: ProjectMetricDataPoint[];
}
