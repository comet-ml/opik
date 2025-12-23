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
  DEFAULT_LEVENSHTEIN_METRIC_CONFIGS,
  OPTIMIZER_OPTIONS,
} from "@/constants/optimizations";
import { DEFAULT_ANTHROPIC_CONFIGS } from "@/constants/llm";
import { getDefaultTemperatureForModel } from "@/lib/modelUtils";
import {
  LLMAnthropicConfigsType,
  LLMOpenAIConfigsType,
  LLMPromptConfigsType,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
  COMPOSED_PROVIDER_TYPE,
} from "@/types/providers";
import { parseComposedProviderType } from "@/lib/provider";
import { COLUMN_TYPE } from "@/types/shared";
import { Filters } from "@/types/filters";

export const getOptimizerLabel = (type: string): string => {
  return OPTIMIZER_OPTIONS.find((opt) => opt.value === type)?.label || type;
};

export const IN_PROGRESS_OPTIMIZATION_STATUSES: OPTIMIZATION_STATUS[] = [
  OPTIMIZATION_STATUS.RUNNING,
  OPTIMIZATION_STATUS.INITIALIZED,
];

export const OPTIMIZATION_ACTIVE_REFETCH_INTERVAL = 5000;

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
        reference_key:
          DEFAULT_JSON_SCHEMA_VALIDATOR_METRIC_CONFIGS.REFERENCE_KEY,
        case_sensitive: DEFAULT_EQUALS_METRIC_CONFIGS.CASE_SENSITIVE,
      };
    case METRIC_TYPE.JSON_SCHEMA_VALIDATOR:
      return {
        reference_key:
          DEFAULT_JSON_SCHEMA_VALIDATOR_METRIC_CONFIGS.REFERENCE_KEY,
      };
    case METRIC_TYPE.G_EVAL:
      return {
        task_introduction: DEFAULT_G_EVAL_METRIC_CONFIGS.TASK_INTRODUCTION,
        evaluation_criteria: DEFAULT_G_EVAL_METRIC_CONFIGS.EVALUATION_CRITERIA,
      };
    case METRIC_TYPE.LEVENSHTEIN:
      return {
        case_sensitive: DEFAULT_LEVENSHTEIN_METRIC_CONFIGS.CASE_SENSITIVE,
        reference_key: DEFAULT_LEVENSHTEIN_METRIC_CONFIGS.REFERENCE_KEY,
      };
    default:
      return {};
  }
};

// @ToDo: remove when we support all params
export const getOptimizationDefaultConfigByProvider = (
  provider: COMPOSED_PROVIDER_TYPE,
  model?: PROVIDER_MODEL_TYPE | "",
): LLMPromptConfigsType => {
  const providerType = parseComposedProviderType(provider);

  if (providerType === PROVIDER_TYPE.OPEN_AI) {
    return {
      temperature: getDefaultTemperatureForModel(model),
    } as LLMOpenAIConfigsType;
  }

  if (providerType === PROVIDER_TYPE.ANTHROPIC) {
    return {
      temperature: DEFAULT_ANTHROPIC_CONFIGS.TEMPERATURE,
    } as LLMAnthropicConfigsType;
  }

  return {};
};
