import { useQuery } from "@tanstack/react-query";
import api, { QueryConfig } from "@/api/api";

export interface ModelComparison {
  id: string;
  name: string;
  description?: string;
  model_ids: string[];
  dataset_names: string[];
  filters?: Record<string, any>;
  results?: ModelComparisonResults;
  created_at: string;
  last_updated_at: string;
  created_by?: string;
  last_updated_by?: string;
}

export interface ModelComparisonResults {
  model_performances: ModelPerformance[];
  cost_comparison: CostComparison;
  accuracy_comparison: AccuracyComparison;
  performance_comparison: PerformanceComparison;
  dataset_comparisons: DatasetComparison[];
}

export interface ModelPerformance {
  model_id: string;
  model_name: string;
  provider: string;
  total_traces: number;
  total_spans: number;
  total_cost: number;
  average_latency: number;
  success_rate: number;
  feedback_scores: Record<string, number>;
  token_usage: TokenUsage;
}

export interface CostComparison {
  model_costs: ModelCost[];
  total_cost_difference: number;
  cost_efficiency_ratio: number;
  most_cost_effective_model: string;
}

export interface ModelCost {
  model_id: string;
  model_name: string;
  total_cost: number;
  cost_per_request: number;
  cost_per_token: number;
  token_usage: TokenUsage;
}

export interface AccuracyComparison {
  metric_comparisons: MetricComparison[];
  best_performing_model: string;
  overall_scores: Record<string, number>;
}

export interface MetricComparison {
  metric_name: string;
  metric_category: string;
  model_scores: ModelMetricScore[];
  best_model: string;
  score_difference: number;
}

export interface ModelMetricScore {
  model_id: string;
  model_name: string;
  score: number;
  sample_size: number;
  confidence: number;
}

export interface PerformanceComparison {
  model_metrics: ModelPerformanceMetrics[];
  fastest_model: string;
  most_reliable_model: string;
}

export interface ModelPerformanceMetrics {
  model_id: string;
  model_name: string;
  average_latency: number;
  p95_latency: number;
  p99_latency: number;
  success_rate: number;
  error_rate: number;
  total_requests: number;
  failed_requests: number;
}

export interface DatasetComparison {
  dataset_name: string;
  model_performances: ModelDatasetPerformance[];
  best_model: string;
  total_items: number;
}

export interface ModelDatasetPerformance {
  model_id: string;
  model_name: string;
  feedback_scores: Record<string, number>;
  average_score: number;
  items_processed: number;
  total_cost: number;
}

export interface TokenUsage {
  total_tokens: number;
  input_tokens: number;
  output_tokens: number;
  average_tokens_per_request: number;
}

export interface ModelComparisonsListResponse {
  page: number;
  size: number;
  total: number;
  content: ModelComparison[];
  sortable_by: string[];
}

type UseModelComparisonsListParams = {
  page?: number;
  size?: number;
  sorting?: string;
  search?: string;
};

const getModelComparisonsList = async (
  { signal }: QueryFunctionContext,
  params: UseModelComparisonsListParams,
) => {
  const { data } = await api.get<ModelComparisonsListResponse>("/api/v1/model-comparisons", {
    signal,
    params: {
      page: params.page || 1,
      size: params.size || 10,
      ...(params.sorting && { sorting: params.sorting }),
      ...(params.search && { search: params.search }),
    },
  });
  return data;
};

export default function useModelComparisonsList(
  params: UseModelComparisonsListParams,
  options?: QueryConfig<ModelComparisonsListResponse>,
) {
  return useQuery({
    queryKey: ["model-comparisons", params],
    queryFn: (context) => getModelComparisonsList(context, params),
    ...options,
  });
}