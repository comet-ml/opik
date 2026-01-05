import { AggregatedFeedbackScore } from "@/types/shared";
import { PROVIDER_MODEL_TYPE } from "./providers";

export enum OPTIMIZATION_STATUS {
  RUNNING = "running",
  COMPLETED = "completed",
  CANCELLED = "cancelled",
  INITIALIZED = "initialized",
  ERROR = "error",
}

export interface StudioMessage {
  role: string;
  content: string;
}

export interface StudioPrompt {
  messages: StudioMessage[];
}

export interface StudioLlmModel {
  model: PROVIDER_MODEL_TYPE;
  parameters?: Record<string, unknown>;
}

export enum METRIC_TYPE {
  EQUALS = "equals",
  JSON_SCHEMA_VALIDATOR = "json_schema_validator",
  G_EVAL = "geval",
  LEVENSHTEIN = "levenshtein_ratio",
}

export interface EqualsMetricParameters {
  case_sensitive?: boolean;
  reference_key?: string;
}

export interface JsonSchemaValidatorMetricParameters {
  reference_key?: string;
}

export interface GEvalMetricParameters {
  task_introduction?: string;
  evaluation_criteria?: string;
}

export interface LevenshteinMetricParameters {
  case_sensitive?: boolean;
  reference_key?: string;
}

export type MetricParameters =
  | EqualsMetricParameters
  | JsonSchemaValidatorMetricParameters
  | GEvalMetricParameters
  | LevenshteinMetricParameters;

export interface StudioMetric {
  type: METRIC_TYPE;
  parameters?: MetricParameters;
}

export interface StudioEvaluation {
  metrics: StudioMetric[];
}

export enum OPTIMIZER_TYPE {
  GEPA = "gepa",
  EVOLUTIONARY = "evolutionary",
  HIERARCHICAL_REFLECTIVE = "hierarchical_reflective",
}

export interface GepaOptimizerParameters {
  model?: string;
  model_parameters?: Record<string, unknown>;
  verbose?: boolean;
  seed?: number;
}

export interface EvolutionaryOptimizerParameters {
  model?: string;
  model_parameters?: Record<string, unknown>;
  population_size?: number;
  num_generations?: number;
  mutation_rate?: number;
  crossover_rate?: number;
  tournament_size?: number;
  elitism_size?: number;
  adaptive_mutation?: boolean;
  enable_moo?: boolean;
  enable_llm_crossover?: boolean;
  output_style_guidance?: string;
  infer_output_style?: boolean;
  n_threads?: number;
  verbose?: boolean;
  seed?: number;
}

export interface HierarchicalReflectiveOptimizerParameters {
  model?: string;
  model_parameters?: Record<string, unknown>;
  convergence_threshold?: number;
  verbose?: boolean;
  seed?: number;
}

export type OptimizerParameters =
  | GepaOptimizerParameters
  | EvolutionaryOptimizerParameters
  | HierarchicalReflectiveOptimizerParameters;

export interface StudioOptimizer {
  type: OPTIMIZER_TYPE;
  parameters?: OptimizerParameters;
}

export interface OptimizationStudioConfig {
  dataset_name: string;
  prompt: StudioPrompt;
  llm_model: StudioLlmModel;
  evaluation: StudioEvaluation;
  optimizer: StudioOptimizer;
}

export interface Optimization {
  id: string;
  name: string;
  dataset_id: string;
  dataset_name: string;
  metadata?: object;
  studio_config?: OptimizationStudioConfig;
  feedback_scores?: AggregatedFeedbackScore[];
  num_trials: number;
  objective_name: string;
  status: OPTIMIZATION_STATUS;
  created_at: string;
  created_by: string;
  last_updated_at: string;
  last_updated_by: string;
}

export interface OptimizationUpdate {
  name?: string;
  status?: OPTIMIZATION_STATUS;
}
