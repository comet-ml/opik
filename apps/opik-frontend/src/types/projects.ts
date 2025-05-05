import { AggregatedFeedbackScore, UsageData } from "@/types/shared";

export interface ProjectDuration {
  p50: number;
  p90: number;
  p99: number;
}

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

export interface ProjectStatistic {
  project_id?: string;
  usage?: UsageData;
  feedback_scores?: AggregatedFeedbackScore[];
  total_estimated_cost?: number;
  duration?: ProjectDuration;
  guardrails_failed_count?: number;
}

export type ProjectWithStatistic = Project & ProjectStatistic;

export type ProjectMetricValue = number | null;

export interface ProjectMetricDataPoint {
  time: string;
  value: ProjectMetricValue;
}

export interface ProjectMetricTrace {
  name: string;
  data: ProjectMetricDataPoint[];
}

export type TransformedDataValueType = null | number | string;
export type TransformedData = { [key: string]: TransformedDataValueType };
