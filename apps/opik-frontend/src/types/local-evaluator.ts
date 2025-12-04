/**
 * Types for local evaluator (eval_app) integration with Playground
 */

export interface LocalMetricParam {
  name: string;
  required: boolean;
  type: string | null;
  default: unknown;
}

export interface LocalMetricDescriptor {
  name: string;
  description: string;
  score_description: string;
  init_params: LocalMetricParam[];
  score_params: LocalMetricParam[];
}

export interface LocalMetricsListResponse {
  metrics: LocalMetricDescriptor[];
}

/**
 * A configured metric for the playground.
 * Each metric can have its own init args and field mapping.
 */
export interface PlaygroundMetricConfig {
  id: string;
  metric_name: string;
  // Custom name for the feedback score. Defaults to metric class name if not set.
  name?: string;
  init_args: Record<string, unknown>;
  // Mapping from metric score() argument names to trace field paths
  // e.g., { "input": "input.messages", "output": "output.output" }
  arguments: Record<string, string>;
}

export interface LocalEvaluationRequest {
  trace_id: string;
  metrics: LocalEvaluationRequestConfig[];
}

export interface LocalEvaluationRequestConfig {
  metric_name: string;
  name?: string;
  init_args: Record<string, unknown>;
  arguments: Record<string, string>;
}

export interface LocalEvaluationResponse {
  trace_id: string;
  metrics_count: number;
  message: string;
}

export const DEFAULT_LOCAL_EVALUATOR_URL = "http://localhost:5001";
