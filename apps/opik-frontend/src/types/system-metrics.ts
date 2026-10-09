export type SystemMetricInstance = {
  service_instance_id: string;
  service_name: string;
  agent_id: string;
  last_seen: string;
};

export type SystemMetricInstances = {
  instances: SystemMetricInstance[];
};

export type SystemMetricPoint = {
  timestamp: string;
  value: number;
  attributes: Record<string, string>;
};

export type SystemMetricSeries = {
  project_id: string;
  service_instance_id: string;
  metric_name: string;
  unit: string;
  from: string;
  to: string;
  truncated: boolean;
  points: SystemMetricPoint[];
};
