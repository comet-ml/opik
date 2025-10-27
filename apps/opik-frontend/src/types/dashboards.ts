export type DashboardType = "prebuilt" | "custom";
export type ChartType = "line" | "bar" | "area" | "stacked_bar" | "stacked_area" | "pie" | "donut";
export type GroupByType = "automatic" | "manual";
export type FilterType = "trace" | "thread";
export type MetricType =
  // Trace metrics
  | "FEEDBACK_SCORES"
  | "TRACE_COUNT"
  | "TOKEN_USAGE"
  | "DURATION"
  | "COST"
  | "GUARDRAILS_FAILED_COUNT"
  | "ERROR_COUNT"
  | "COMPLETION_TOKENS"
  | "PROMPT_TOKENS"
  | "TOTAL_TOKENS"
  | "INPUT_COUNT"
  | "OUTPUT_COUNT"
  | "METADATA_COUNT"
  | "TAGS_AVERAGE"
  | "TRACE_WITH_ERRORS_PERCENT"
  | "GUARDRAILS_PASS_RATE"
  | "AVG_COST_PER_TRACE"
  // Span metrics (aggregated at trace level)
  | "SPAN_COUNT"
  | "LLM_SPAN_COUNT"
  | "SPAN_DURATION"
  // Span metrics (direct span-level)
  | "SPAN_TOTAL_COUNT"
  | "SPAN_ERROR_COUNT"
  | "SPAN_INPUT_COUNT"
  | "SPAN_OUTPUT_COUNT"
  | "SPAN_METADATA_COUNT"
  | "SPAN_TAGS_AVERAGE"
  | "SPAN_COST"
  | "SPAN_AVG_COST"
  | "SPAN_FEEDBACK_SCORES"
  | "SPAN_TOKEN_USAGE"
  | "SPAN_PROMPT_TOKENS"
  | "SPAN_COMPLETION_TOKENS"
  | "SPAN_TOTAL_TOKENS"
  // Thread metrics
  | "THREAD_COUNT"
  | "THREAD_DURATION"
  | "THREAD_FEEDBACK_SCORES";

export type TimeInterval = "HOURLY" | "DAILY" | "WEEKLY";

export interface ChartPosition {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface GroupByConfig {
  field?: string;
  type?: GroupByType;
  limit_top_n?: number;
}

export interface DataSeries {
  id?: string;
  project_id?: string;
  metric_type: MetricType;
  name?: string;
  filters?: unknown[]; // TraceFilter[] or ThreadFilter[]
  color?: string;
  order?: number;
  created_at?: string;
}

export interface DashboardChart {
  id?: string;
  dashboard_id?: string;
  name: string;
  description?: string;
  chart_type: ChartType;
  position?: ChartPosition;
  data_series?: DataSeries[];
  group_by?: GroupByConfig;
  created_at?: string;
  created_by?: string;
  last_updated_at?: string;
  last_updated_by?: string;
}

export interface Dashboard {
  id: string;
  workspace_id?: string;
  name: string;
  description?: string;
  type: DashboardType;
  is_default?: boolean;
  project_ids?: string[];
  charts?: DashboardChart[];
  created_at?: string;
  created_by?: string;
  last_updated_at?: string;
  last_updated_by?: string;
}

export interface SavedFilter {
  id: string;
  workspace_id?: string;
  project_id: string;
  name: string;
  description?: string;
  filters: unknown[]; // TraceFilter[] or ThreadFilter[]
  filter_type: FilterType;
  created_at?: string;
  created_by?: string;
  last_updated_at?: string;
  last_updated_by?: string;
}

export interface DataPoint<T = number> {
  time: string;
  value: T | null;
}

export interface SeriesData {
  name: string;
  data: DataPoint[];
}

export interface ChartDataRequest {
  interval: TimeInterval;
  interval_start: string;
  interval_end: string;
}

export interface ChartDataResponse {
  chart_id: string;
  interval: TimeInterval;
  series: SeriesData[];
}



