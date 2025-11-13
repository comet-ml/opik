import {
  OPTIMIZER_TYPE,
  OPTIMIZATION_STATUS,
  METRIC_TYPE,
  OptimizerParameters,
  MetricParameters,
} from "@/types/optimizations";
import {
  DEFAULT_GEPA_OPTIMIZER_CONFIGS,
  DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS,
  DEFAULT_HIERARCHICAL_REFLECTIVE_OPTIMIZER_CONFIGS,
  DEFAULT_EQUALS_METRIC_CONFIGS,
  DEFAULT_JSON_SCHEMA_VALIDATOR_METRIC_CONFIGS,
  DEFAULT_G_EVAL_METRIC_CONFIGS,
} from "@/constants/optimizations";
import { COLUMN_TYPE } from "@/types/shared";
import { Filters } from "@/types/filters";

export const ACTIVE_OPTIMIZATION_FILTER: Filters = [
  {
    id: "status-running",
    field: "status",
    type: COLUMN_TYPE.string,
    operator: "=",
    value: OPTIMIZATION_STATUS.RUNNING,
  },
];

export const getDefaultOptimizerConfig = (
  optimizerType: OPTIMIZER_TYPE,
): Partial<OptimizerParameters> => {
  switch (optimizerType) {
    case OPTIMIZER_TYPE.GEPA:
      return {
        verbose: DEFAULT_GEPA_OPTIMIZER_CONFIGS.VERBOSE,
        seed: DEFAULT_GEPA_OPTIMIZER_CONFIGS.SEED,
      };
    case OPTIMIZER_TYPE.EVOLUTIONARY:
      return {
        population_size: DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.POPULATION_SIZE,
        num_generations: DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.NUM_GENERATIONS,
        mutation_rate: DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.MUTATION_RATE,
        crossover_rate: DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.CROSSOVER_RATE,
        tournament_size: DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.TOURNAMENT_SIZE,
        elitism_size: DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.ELITISM_SIZE,
        adaptive_mutation:
          DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.ADAPTIVE_MUTATION,
        enable_moo: DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.ENABLE_MOO,
        enable_llm_crossover:
          DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.ENABLE_LLM_CROSSOVER,
        output_style_guidance:
          DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.OUTPUT_STYLE_GUIDANCE,
        infer_output_style:
          DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.INFER_OUTPUT_STYLE,
        n_threads: DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.N_THREADS,
        verbose: DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.VERBOSE,
        seed: DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.SEED,
      };
    case OPTIMIZER_TYPE.HIERARCHICAL_REFLECTIVE:
      return {
        convergence_threshold:
          DEFAULT_HIERARCHICAL_REFLECTIVE_OPTIMIZER_CONFIGS.CONVERGENCE_THRESHOLD,
        verbose: DEFAULT_HIERARCHICAL_REFLECTIVE_OPTIMIZER_CONFIGS.VERBOSE,
        seed: DEFAULT_HIERARCHICAL_REFLECTIVE_OPTIMIZER_CONFIGS.SEED,
      };
    default:
      return {};
  }
};

export const getDefaultMetricConfig = (
  metricType: METRIC_TYPE,
): Partial<MetricParameters> => {
  switch (metricType) {
    case METRIC_TYPE.EQUALS:
      return {
        case_sensitive: DEFAULT_EQUALS_METRIC_CONFIGS.CASE_SENSITIVE,
      };
    case METRIC_TYPE.JSON_SCHEMA_VALIDATOR:
      return {
        schema: DEFAULT_JSON_SCHEMA_VALIDATOR_METRIC_CONFIGS.SCHEMA,
      };
    case METRIC_TYPE.G_EVAL:
      return {
        task_introduction: DEFAULT_G_EVAL_METRIC_CONFIGS.TASK_INTRODUCTION,
        evaluation_criteria: DEFAULT_G_EVAL_METRIC_CONFIGS.EVALUATION_CRITERIA,
      };
    default:
      return {};
  }
};
