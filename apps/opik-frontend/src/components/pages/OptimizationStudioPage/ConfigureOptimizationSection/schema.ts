import { z } from "zod";
import {
  OPTIMIZER_TYPE,
  METRIC_TYPE,
  OptimizationStudioConfig,
  OptimizationStudio,
} from "@/types/optimizations";
import { LLMMessage, LLM_MESSAGE_ROLE } from "@/types/llm";
import { generateDefaultLLMPromptMessage } from "@/lib/llm";
import {
  getDefaultOptimizerConfig,
  getDefaultMetricConfig,
} from "@/lib/optimizations";
import { PROVIDER_MODEL_TYPE } from "@/types/providers";

export const GepaOptimizerParamsSchema = z.object({
  model: z.string().optional(),
  model_parameters: z.record(z.unknown()).optional(),
  verbose: z.boolean().optional(),
  seed: z.number().optional(),
});

// export const EvolutionaryOptimizerParamsSchema = z.object({
//   model: z.string().optional(),
//   model_parameters: z.record(z.unknown()).optional(),
//   population_size: z.number().min(1, "Must be at least 1").optional(),
//   num_generations: z.number().min(1, "Must be at least 1").optional(),
//   mutation_rate: z
//     .number()
//     .min(0, "Must be between 0 and 1")
//     .max(1, "Must be between 0 and 1")
//     .optional(),
//   crossover_rate: z
//     .number()
//     .min(0, "Must be between 0 and 1")
//     .max(1, "Must be between 0 and 1")
//     .optional(),
//   tournament_size: z.number().min(1, "Must be at least 1").optional(),
//   elitism_size: z.number().min(0, "Must be at least 0").optional(),
//   adaptive_mutation: z.boolean().optional(),
//   enable_moo: z.boolean().optional(),
//   enable_llm_crossover: z.boolean().optional(),
//   output_style_guidance: z.string().optional(),
//   infer_output_style: z.boolean().optional(),
//   n_threads: z.number().min(1, "Must be at least 1").optional(),
//   verbose: z.boolean().optional(),
//   seed: z.number().optional(),
// });

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
  case_sensitive: z.boolean(),
  reference_key: z.string().optional(),
});

export const JsonSchemaValidatorMetricParamsSchema = z.object({
  schema: z
    .record(z.unknown())
    .refine((obj) => Object.keys(obj).length > 0, "Schema cannot be empty"),
});

export const GEvalMetricParamsSchema = z.object({
  task_introduction: z.string().min(1, "Task introduction is required"),
  evaluation_criteria: z.string().min(1, "Evaluation criteria is required"),
});

export const LevenshteinMetricParamsSchema = z.object({
  normalize: z.boolean().optional(),
  reference_key: z.string().optional(),
});

const BaseOptimizationConfigSchema = z.object({
  datasetName: z.string().min(1, "Dataset is required"),
  optimizerType: z.nativeEnum(OPTIMIZER_TYPE),
  optimizerParams: z.union([
    GepaOptimizerParamsSchema,
    // EvolutionaryOptimizerParamsSchema,
    HierarchicalReflectiveOptimizerParamsSchema,
  ]),
  messages: z
    .array(z.custom<LLMMessage>())
    .min(1, "At least one message is required"),
  modelName: z.nativeEnum(PROVIDER_MODEL_TYPE),
  modelConfig: z.record(z.unknown()).default({ temperature: 1.0 }),
});

const EqualsMetricConfigSchema = BaseOptimizationConfigSchema.extend({
  metricType: z.literal(METRIC_TYPE.EQUALS),
  metricParams: EqualsMetricParamsSchema,
});

const JsonSchemaValidatorMetricConfigSchema =
  BaseOptimizationConfigSchema.extend({
    metricType: z.literal(METRIC_TYPE.JSON_SCHEMA_VALIDATOR),
    metricParams: JsonSchemaValidatorMetricParamsSchema,
  });

const GEvalMetricConfigSchema = BaseOptimizationConfigSchema.extend({
  metricType: z.literal(METRIC_TYPE.G_EVAL),
  metricParams: GEvalMetricParamsSchema,
});

const LevenshteinMetricConfigSchema = BaseOptimizationConfigSchema.extend({
  metricType: z.literal(METRIC_TYPE.LEVENSHTEIN),
  metricParams: LevenshteinMetricParamsSchema,
});

export const OptimizationConfigSchema = z.discriminatedUnion("metricType", [
  EqualsMetricConfigSchema,
  JsonSchemaValidatorMetricConfigSchema,
  GEvalMetricConfigSchema,
  LevenshteinMetricConfigSchema,
]);

export type OptimizationConfigFormType = z.infer<
  typeof OptimizationConfigSchema
>;

export const convertOptimizationStudioToFormData = (
  optimization?: Partial<OptimizationStudio> | null,
): OptimizationConfigFormType => {
  const existingConfig = optimization?.studio_config?.llm_model?.parameters as
    | Record<string, unknown>
    | undefined;

  const messages: LLMMessage[] =
    optimization?.studio_config?.prompt?.messages?.map((m) => ({
      id: crypto.randomUUID(),
      role: m.role as LLM_MESSAGE_ROLE,
      content: m.content,
    })) || [
      generateDefaultLLMPromptMessage({ role: LLM_MESSAGE_ROLE.system }),
      generateDefaultLLMPromptMessage({ role: LLM_MESSAGE_ROLE.user }),
    ];

  console.log(optimization, "OPTIMIZATION_LOL");

  const optimizerType =
    (optimization?.studio_config?.optimizer.type as OPTIMIZER_TYPE) ||
    OPTIMIZER_TYPE.GEPA;

  const metricType =
    (optimization?.studio_config?.evaluation.metrics[0]?.type as METRIC_TYPE) ||
    METRIC_TYPE.EQUALS;

  return {
    datasetName: optimization?.dataset_name || "",
    optimizerType,
    optimizerParams:
      optimization?.studio_config?.optimizer.parameters ||
      getDefaultOptimizerConfig(optimizerType),
    metricType,
    metricParams:
      optimization?.studio_config?.evaluation?.metrics?.[0]?.parameters ||
      getDefaultMetricConfig(metricType),
    messages,
    // Expand it later
    modelName:
      optimization?.studio_config?.llm_model?.model ||
      PROVIDER_MODEL_TYPE.GPT_4O_MINI,
    modelConfig: existingConfig || { temperature: 1.0 },
  } as OptimizationConfigFormType;
};

export const convertFormDataToStudioConfig = (
  formData: OptimizationConfigFormType,
): OptimizationStudioConfig => {
  const messages = formData.messages.map((m) => ({
    role: m.role,
    content:
      typeof m.content === "string" ? m.content : JSON.stringify(m.content),
  }));

  return {
    dataset_name: formData.datasetName,
    prompt: {
      messages,
    },
    llm_model: {
      model: formData.modelName,
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
