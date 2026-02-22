import {
  AggregatedDuration,
  AggregatedFeedbackScore,
  UsageData,
} from "@/types/shared";

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

export type ProjectErrorCount = {
  count: number;
  deviation: number;
  deviation_percentage: number;
};

export interface ProjectStatistic {
  project_id?: string;
  usage?: UsageData;
  usage_sum?: UsageData;
  feedback_scores?: AggregatedFeedbackScore[];
  total_estimated_cost_sum?: number;
  duration?: AggregatedDuration;
  guardrails_failed_count?: number;
  error_count?: ProjectErrorCount;
  trace_count?: number;
  thread_count?: number;
}

export type ProjectWithStatistic = Project & ProjectStatistic;

export interface ProjectMetricDataPoint {
  time: string;
  value: number | null;
}

export interface ProjectMetricTrace {
  name: string;
  data: ProjectMetricDataPoint[];
}

export type TransformedDataValueType = null | number | string;
export type TransformedData = { [key: string]: TransformedDataValueType };
