export enum ALERT_EVENT_TYPE {
  "trace:errors" = "trace:errors",
  "trace:feedback_score" = "trace:feedback_score",
  "trace_thread:feedback_score" = "trace_thread:feedback_score",
  "prompt:created" = "prompt:created",
  "prompt:committed" = "prompt:committed",
  "span:guardrails_triggered" = "span:guardrails_triggered",
  "prompt:deleted" = "prompt:deleted",
}

export enum ALERT_TRIGGER_CONFIG_TYPE {
  "scope:project" = "scope:project",
  "threshold:feedback_score" = "threshold:feedback_score",
}

export interface AlertTriggerConfig {
  id?: string;
  alert_trigger_id?: string;
  type: ALERT_TRIGGER_CONFIG_TYPE;
  config_value: Record<string, string>;
  created_at?: string;
  created_by?: string;
  last_updated_at?: string;
  last_updated_by?: string;
}

export interface AlertTrigger {
  id?: string;
  alert_id?: string;
  event_type: ALERT_EVENT_TYPE;
  trigger_configs?: AlertTriggerConfig[];
  created_at?: string;
  created_by?: string;
}

export interface Webhook {
  id?: string;
  name?: string;
  url: string;
  secret_token?: string;
  headers?: Record<string, string>;
  created_at?: string;
  created_by?: string;
  last_updated_at?: string;
  last_updated_by?: string;
}

export interface Alert {
  id?: string;
  name: string;
  enabled: boolean;
  webhook: Webhook;
  triggers: AlertTrigger[];
  created_at?: string;
  created_by?: string;
  last_updated_at?: string;
  last_updated_by?: string;
}

export interface AlertsListResponse {
  size: number;
  page: number;
  content: Alert[];
  total: number;
  sortable_by: string[];
}

export interface WebhookTestResult {
  status: "success" | "failure";
  status_code: number;
  request_body: string;
  error_message?: string;
}
