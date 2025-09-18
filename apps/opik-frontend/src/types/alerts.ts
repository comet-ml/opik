export enum ALERT_EVENT_TYPE {
  trace_errors = "trace_errors",
  guardrails = "guardrails",
  prompt_creation = "prompt_creation",
  prompt_commit = "prompt_commit",
  trace_score = "trace_score",
  thread_score = "thread_score",
}

export enum ALERT_CONDITION_TYPE {
  project_scope = "project_scope",
  value_threshold = "value_threshold",
}

export interface ProjectScopeCondition {
  type: ALERT_CONDITION_TYPE.project_scope;
  value: string[];
}

export interface ValueThresholdCondition {
  type: ALERT_CONDITION_TYPE.value_threshold;
  lower_bound?: number;
  upper_bound?: number;
}

export type AlertCondition = ProjectScopeCondition | ValueThresholdCondition;

export interface AlertEvent {
  event_type: ALERT_EVENT_TYPE;
  conditions?: AlertCondition[];
}

export interface Alert {
  id?: string;
  name: string;
  enabled: boolean;
  url: string;
  secret_token?: string;
  headers?: Record<string, string>;
  events: AlertEvent[];
  created_at: string;
  created_by: string;
  last_updated_at: string;
  last_updated_by: string;
}

export interface AlertsListResponse {
  size: number;
  page: number;
  content: Alert[];
  total: number;
  sortable_by: string[];
}
