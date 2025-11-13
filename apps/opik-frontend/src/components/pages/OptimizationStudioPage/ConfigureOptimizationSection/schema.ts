import { z } from "zod";
import {
  OPTIMIZER_TYPE,
  METRIC_TYPE,
  OptimizationStudioConfig,
  Optimization,
} from "@/types/optimizations";
import { LLMMessage, LLM_MESSAGE_ROLE } from "@/types/llm";
import { generateDefaultLLMPromptMessage } from "@/lib/llm";
import {
  getDefaultOptimizerConfig,
  getDefaultMetricConfig,
} from "@/lib/optimizations";

export const GepaOptimizerParamsSchema = z.object({
  model: z.string().optional(),
  model_parameters: z.record(z.unknown()).optional(),
  verbose: z.boolean().optional(),
  seed: z.number().optional(),
});

export const EvolutionaryOptimizerParamsSchema = z.object({
  model: z.string().optional(),
  model_parameters: z.record(z.unknown()).optional(),
  population_size: z.number().min(1, "Must be at least 1").optional(),
  num_generations: z.number().min(1, "Must be at least 1").optional(),
  mutation_rate: z
    .number()
    .min(0, "Must be between 0 and 1")
    .max(1, "Must be between 0 and 1")
    .optional(),
  crossover_rate: z
    .number()
    .min(0, "Must be between 0 and 1")
    .max(1, "Must be between 0 and 1")
    .optional(),
  tournament_size: z.number().min(1, "Must be at least 1").optional(),
  elitism_size: z.number().min(0, "Must be at least 0").optional(),
  adaptive_mutation: z.boolean().optional(),
  enable_moo: z.boolean().optional(),
  enable_llm_crossover: z.boolean().optional(),
  output_style_guidance: z.string().optional(),
  infer_output_style: z.boolean().optional(),
  n_threads: z.number().min(1, "Must be at least 1").optional(),
  verbose: z.boolean().optional(),
  seed: z.number().optional(),
});

export const HierarchicalReflectiveOptimizerParamsSchema = z.object({
  model: z.string().optional(),
  model_parameters: z.record(z.unknown()).optional(),
  convergence_threshold: z
    .number()
    .min(0, "Must be between 0 and 1")
    .max(1, "Must be between 0 and 1")
    .optional(),
  verbose: z.boolean().optional(),
  seed: z.number().optional(),
});

export const EqualsMetricParamsSchema = z.object({
  case_sensitive: z.boolean().optional(),
  reference_key: z.string().optional(),
});

export const JsonSchemaValidatorMetricParamsSchema = z.object({
  schema: z.record(z.unknown()).optional(),
});

export const GEvalMetricParamsSchema = z.object({
  task_introduction: z.string().optional(),
  evaluation_criteria: z.string().optional(),
});

export const OptimizationConfigSchema = z.object({
  datasetId: z.string().min(1, "Dataset is required"),
  optimizerType: z.nativeEnum(OPTIMIZER_TYPE),
  optimizerParams: z
    .union([
      GepaOptimizerParamsSchema,
      EvolutionaryOptimizerParamsSchema,
      HierarchicalReflectiveOptimizerParamsSchema,
    ])
    .optional(),
  metricType: z.nativeEnum(METRIC_TYPE),
  metricParams: z
    .union([
      EqualsMetricParamsSchema,
      JsonSchemaValidatorMetricParamsSchema,
      GEvalMetricParamsSchema,
    ])
    .optional(),
  messages: z
    .array(z.custom<LLMMessage>())
    .min(1, "At least one message is required"),
  modelProvider: z.string().min(1, "Model provider is required"),
  modelName: z.string().min(1, "Model name is required"),
  modelConfig: z.record(z.unknown()).default({ temperature: 1.0 }),
});

export type OptimizationConfigFormType = z.infer<
  typeof OptimizationConfigSchema
>;

export const convertOptimizationToFormData = (
  optimization?: Partial<Optimization> | null,
): OptimizationConfigFormType => {
  const existingConfig = optimization?.studio_config?.llm_model.parameters as
    | Record<string, unknown>
    | undefined;

  const messages: LLMMessage[] =
    optimization?.studio_config?.prompt.messages.map((m) => ({
      id: crypto.randomUUID(),
      role: m.role as LLM_MESSAGE_ROLE,
      content: m.content,
    })) || [
      generateDefaultLLMPromptMessage({ role: LLM_MESSAGE_ROLE.system }),
      generateDefaultLLMPromptMessage({ role: LLM_MESSAGE_ROLE.user }),
    ];

  const optimizerType =
    (optimization?.studio_config?.optimizer.type as OPTIMIZER_TYPE) ||
    OPTIMIZER_TYPE.GEPA;

  const metricType =
    (optimization?.studio_config?.evaluation.metrics[0]?.type as METRIC_TYPE) ||
    METRIC_TYPE.EQUALS;

  return {
    datasetId: optimization?.dataset_id || "",
    optimizerType,
    optimizerParams:
      optimization?.studio_config?.optimizer.parameters ||
      getDefaultOptimizerConfig(optimizerType),
    metricType,
    metricParams:
      optimization?.studio_config?.evaluation.metrics[0]?.parameters ||
      getDefaultMetricConfig(metricType),
    messages,
    modelProvider: optimization?.studio_config?.llm_model.provider || "",
    modelName: optimization?.studio_config?.llm_model.name || "",
    modelConfig: existingConfig || { temperature: 1.0 },
  };
};

export const convertFormDataToStudioConfig = (
  formData: OptimizationConfigFormType,
  datasetName: string,
): OptimizationStudioConfig => {
  const messages = formData.messages.map((m) => ({
    role: m.role,
    content: m.content,
  }));

  return {
    dataset_name: datasetName,
    prompt: {
      messages,
    },
    llm_model: {
      provider: formData.modelProvider,
      name: formData.modelName,
      parameters: formData.modelConfig,
    },
    evaluation: {
      metrics: [
        {
          type: formData.metricType,
          parameters: formData.metricParams || {},
        },
      ],
    },
    optimizer: {
      type: formData.optimizerType,
      parameters: formData.optimizerParams || {},
    },
  };
};
