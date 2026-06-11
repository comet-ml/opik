export enum ISSUE_SEVERITY {
  high = "high",
  medium = "medium",
  low = "low",
}

export enum ISSUE_CATEGORY {
  tool_failure = "tool_failure",
  hallucination = "hallucination",
  timeout = "timeout",
  quality = "quality",
  cost = "cost",
  other = "other",
}

export enum ISSUE_STATUS {
  open = "open",
  resolved = "resolved",
  archived = "archived",
}

export enum SIGNALS_SORT {
  severity = "severity",
  occurrences = "occurrences",
  last_seen = "last_seen",
  first_seen = "first_seen",
}

export interface OccurrencePoint {
  time: string;
  count: number;
}

export interface IssueExampleTrace {
  id: string;
  duration: number;
  span_count: number;
  cost: number;
  model: string;
  last_updated_at: string;
}

export enum PROMPT_DIFF_LINE_TYPE {
  context = "context",
  added = "added",
  removed = "removed",
}

export interface PromptDiffLine {
  type: PROMPT_DIFF_LINE_TYPE;
  text: string;
}

export interface OllieFix {
  analyzed_traces: number;
  root_cause: string;
  // Estimated share (0..1) of affected traces the fix would resolve
  resolution_rate: number;
  // Unified line-based diff of the suggested system-prompt change
  suggested_prompt_change?: PromptDiffLine[];
}

export interface Issue {
  id: string;
  project_id: string;
  name: string;
  severity: ISSUE_SEVERITY;
  category: ISSUE_CATEGORY;
  status: ISSUE_STATUS;
  // Short one-line description shown in the list
  short_description: string;
  // Long description shown in the detail "Summary" section
  summary: string;
  occurrences: number;
  users_impacted: number;
  // Share of traces affected, expressed as a fraction (0..1)
  rate: number;
  first_seen_at: string;
  last_seen_at: string;
  ollie_fix?: OllieFix;
  occurrences_over_time: OccurrencePoint[];
  example_traces: IssueExampleTrace[];
}

export interface IssuesListResponse {
  content: Issue[];
  total: number;
  sortable_by: string[];
}

export interface SignalsStatValue {
  value: number;
  // Trend vs previous period. `percentage` trends are fractions (0.12 = +12%),
  // `absolute` trends are raw deltas (+3 issues).
  trend?: number;
  trend_type?: "percentage" | "absolute";
  // Whether an increase is good (e.g. resolved) or bad (e.g. open issues)
  trend_direction_positive?: boolean;
}

export interface SignalsStats {
  traces_affected: SignalsStatValue;
  open_issues: SignalsStatValue;
  resolved_this_week: SignalsStatValue;
  last_scan_at: string;
}
