// Types mirror the Agent Insights backend API
// (apps/opik-backend .../api/AgentInsightsIssue*.java, served at
// /v1/private/agent-insights). Snake_case fields match the JSON payloads.

export enum AGENT_INSIGHTS_ISSUE_STATUS {
  open = "open",
  resolved = "resolved",
  closed = "closed",
}

export enum AGENT_INSIGHTS_ISSUE_SEVERITY {
  critical = "critical",
  high = "high",
  medium = "medium",
  low = "low",
}

export interface AgentInsightsIssueDetailMetadata {
  // Resolved ids of traces that exhibit the issue, used for the affected-traces sample.
  example_trace_ids?: string[];
  percent?: number;
  confidence?: number;
  confidence_justification?: string;
}

// Per-day breakdown row, used for the occurrence-over-time chart.
export interface AgentInsightsIssueDetail {
  report_day: string;
  count: number;
  total_count: number;
  users_impacted: number;
  total_users: number;
  metadata?: AgentInsightsIssueDetailMetadata;
}

// List item — metrics are aggregated over the requested time window.
export interface AgentInsightsIssue {
  id: string;
  name: string;
  description?: string;
  cause?: string;
  suggested_fix?: string;
  status: AGENT_INSIGHTS_ISSUE_STATUS;
  severity?: AGENT_INSIGHTS_ISSUE_SEVERITY;
  traces_query?: string;
  // Cross-day sum over the window (overall scale).
  total_occurrences: number;
  // Count on the most recent report day — matches the issue prose.
  latest_count: number;
  total: number;
  users_impacted: number;
  total_users: number;
  first_seen?: string;
  last_seen?: string;
  days_reported: number;
  created_by?: string;
  created_at?: string;
  last_updated_by?: string;
  last_updated_at?: string;
}

// Detail response: the issue plus its per-day breakdown within the window.
export interface AgentInsightsIssueWithDetails {
  id: string;
  name: string;
  description?: string;
  cause?: string;
  suggested_fix?: string;
  status: AGENT_INSIGHTS_ISSUE_STATUS;
  severity?: AGENT_INSIGHTS_ISSUE_SEVERITY;
  traces_query?: string;
  created_by?: string;
  created_at?: string;
  last_updated_by?: string;
  last_updated_at?: string;
  details: AgentInsightsIssueDetail[];
}

export interface AgentInsightsIssuesPage {
  page: number;
  size: number;
  total: number;
  content: AgentInsightsIssue[];
}

// Per-(workspace, project) job that produces the insights report.
export enum AGENT_INSIGHTS_JOB_STATUS {
  enabled = "enabled",
  disabled = "disabled",
}

export interface AgentInsightsJob {
  id: string;
  project_id: string;
  status: AGENT_INSIGHTS_JOB_STATUS;
  // When a diagnostic report was last generated (incl. "all clear"); unaffected
  // by resolving/reopening issues. Used for the "Last scan" header.
  last_scan_at?: string;
  // Set when a run fails, cleared on the next successful report.
  last_failure_reason?: string;
  last_failure_detail?: string;
  last_failed_at?: string;
  created_at?: string;
  created_by?: string;
  last_updated_at?: string;
  last_updated_by?: string;
}
