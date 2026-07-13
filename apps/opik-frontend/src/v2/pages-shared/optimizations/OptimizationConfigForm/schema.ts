import { z } from "zod";
import { pythonLanguage } from "@codemirror/lang-python";
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
  getOptimizationDefaultConfigByProvider,
} from "@/lib/optimizations";
import { getProviderFromModel } from "@/lib/provider";
import { sanitizeConfigForRequest } from "@/lib/modelUtils";
import { PROVIDER_MODEL_TYPE, LLMPromptConfigsType } from "@/types/providers";

export const GepaOptimizerParamsSchema = z.object({
  model: z.string().optional(),
  model_parameters: z.record(z.unknown()).optional(),
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
  case_sensitive: z.boolean(),
  reference_key: z.string().min(1, "Reference key is required"),
});

export const JsonSchemaValidatorMetricParamsSchema = z.object({
  reference_key: z.string().min(1, "Reference key is required"),
  case_sensitive: z.boolean().optional(),
});

export const GEvalMetricParamsSchema = z.object({
  task_introduction: z.string().min(1, "Task introduction is required"),
  evaluation_criteria: z.string().min(1, "Evaluation criteria is required"),
});

// Client-side Python syntax check using the error-tolerant Lezer grammar that
// @codemirror/lang-python already ships (no extra dependency). We reuse the
// language's parser directly instead of building an EditorState, then walk the
// resulting tree for error nodes. Lezer recovers from syntax errors by
// inserting nodes whose type reports `isError`, so a valid program yields none.
// This only catches *syntax* problems (unclosed brackets, bad indentation,
// stray tokens) — it never inspects semantics, so it can't false-positive on
// otherwise-valid Python (e.g. metrics that read `kwargs["x"]`).
export const hasPythonSyntaxError = (code: string): boolean => {
  const tree = pythonLanguage.parser.parse(code);
  let hasError = false;
  tree.iterate({
    enter: (node) => {
      if (node.type.isError) {
        hasError = true;
        return false;
      }
      return undefined;
    },
  });
  return hasError;
};

export const CodeMetricParamsSchema = z
  .object({
    code: z.string().min(1, "Python code is required"),
    // Rename-capable `score()` param → dataset column map. Shape matches the
    // backend `_build_code_metric` arguments contract (plain column names, not
    // trace paths). Optional/partial: unmapped params fall back to same-named
    // columns backend-side, so only explicit renames need entries here.
    arguments: z.record(z.string()).optional(),
  })
  .superRefine((params, ctx) => {
    // `.min(1)` above already reports empty code; only run the syntax check when
    // there is something to parse so we don't double-report on an empty editor.
    if (params.code && hasPythonSyntaxError(params.code)) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        // Anchor to the `code` field so the message flows through
        // `errors?.code?.message` into CodeMetricConfigs' FormErrorSkeleton.
        path: ["code"],
        message: "Python code has a syntax error",
      });
    }
  });

export const LevenshteinMetricParamsSchema = z.object({
  normalize: z.boolean().optional(),
  reference_key: z.string().min(1, "Reference key is required"),
});

export const NumericalSimilarityMetricParamsSchema = z.object({
  reference_key: z.string().min(1, "Reference key is required"),
});

const isMessageEmpty = (message: LLMMessage): boolean => {
  const content = message.content;
  if (typeof content === "string") {
    return content.trim().length === 0;
  }
  if (Array.isArray(content)) {
    return content.every((part) => {
      if (part.type === "text") {
        return part.text.trim().length === 0;
      }
      return false;
    });
  }
  return true;
};

const BaseOptimizationConfigSchema = z.object({
  name: z.string().optional(),
  datasetId: z.string().min(1, "Test suite is required"),
  optimizerType: z.nativeEnum(OPTIMIZER_TYPE),
  optimizerParams: z.union([
    GepaOptimizerParamsSchema,
    // EvolutionaryOptimizerParamsSchema,
    HierarchicalReflectiveOptimizerParamsSchema,
  ]),
  messages: z
    .array(z.custom<LLMMessage>())
    .min(1, "At least one message is required")
    // Emit a per-message issue (path [index, "content"]) instead of one
    // array-level error, so each empty message card renders its own red border
    // + inline text rather than a single banner. An array-root error would
    // shadow these per-index ones (react-hook-form #7742), but `.min(1)` above
    // is the only root error and it fires solely on an empty list — unreachable
    // in the UI (the remove button is hidden at one message) — so they never
    // collide.
    .superRefine((messages, ctx) => {
      messages.forEach((message, index) => {
        if (isMessageEmpty(message)) {
          ctx.addIssue({
            code: z.ZodIssueCode.custom,
            path: [index, "content"],
            message: "Message is required",
          });
        }
      });
    }),
  // Accept any string, not just the static PROVIDER_MODEL_TYPE enum: models
  // now come from the backend registry and can include ids that weren't in
  // the FE enum at release time (see OPIK-5021). The enum itself is going
  // away in OPIK-5022.
  modelName: z.string().min(1, "Model is required"),
  modelConfig: z
    .object({
      temperature: z.number().optional(),
    })
    .passthrough(),
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

const NumericalSimilarityMetricConfigSchema =
  BaseOptimizationConfigSchema.extend({
    metricType: z.literal(METRIC_TYPE.NUMERICAL_SIMILARITY),
    metricParams: NumericalSimilarityMetricParamsSchema,
  });

const CodeMetricConfigSchema = BaseOptimizationConfigSchema.extend({
  metricType: z.literal(METRIC_TYPE.CODE),
  metricParams: CodeMetricParamsSchema,
});

export const OptimizationConfigSchema = z.discriminatedUnion("metricType", [
  EqualsMetricConfigSchema,
  JsonSchemaValidatorMetricConfigSchema,
  GEvalMetricConfigSchema,
  LevenshteinMetricConfigSchema,
  NumericalSimilarityMetricConfigSchema,
  CodeMetricConfigSchema,
]);

export type OptimizationConfigFormType = z.infer<
  typeof OptimizationConfigSchema
>;

const getDefaultModelConfig = (model: PROVIDER_MODEL_TYPE) => {
  const provider = getProviderFromModel(model);
  return getOptimizationDefaultConfigByProvider(provider, model);
};

export const convertOptimizationStudioToFormData = (
  optimization?: Partial<Optimization> | null,
  // Models the workspace can actually run (resolved from configured providers).
  // The default model is picked from this list so we never seed a model the
  // gateway can't resolve; pass `[]` while provider data is still loading.
  availableModels: string[] = [],
): OptimizationConfigFormType => {
  const existingConfig = optimization?.studio_config?.llm_model?.parameters as
    | LLMPromptConfigsType
    | undefined;

  const hasExistingConfig =
    existingConfig && Object.keys(existingConfig).length > 0;

  const messages: LLMMessage[] =
    optimization?.studio_config?.prompt?.messages?.map((m) => ({
      id: crypto.randomUUID(),
      role: m.role as LLM_MESSAGE_ROLE,
      content: m.content,
    })) || [generateDefaultLLMPromptMessage({ role: LLM_MESSAGE_ROLE.user })];

  const optimizerType =
    (optimization?.studio_config?.optimizer.type as OPTIMIZER_TYPE) ||
    OPTIMIZER_TYPE.GEPA;

  const metricType =
    (optimization?.studio_config?.evaluation.metrics[0]?.type as METRIC_TYPE) ||
    METRIC_TYPE.EQUALS;

  // Resolve a model the workspace can run: prefer the configured one (rerun /
  // template) when available, otherwise default to the first available model,
  // otherwise "" (no provider configured) so the "Model is required" validation
  // blocks submission instead of seeding a model the gateway can't resolve.
  const configuredModel = optimization?.studio_config?.llm_model?.model;
  const modelName =
    configuredModel && availableModels.includes(configuredModel)
      ? configuredModel
      : availableModels[0] ?? "";

  const defaultConfig = modelName
    ? getDefaultModelConfig(modelName as PROVIDER_MODEL_TYPE)
    : ({} as LLMPromptConfigsType);
  // Keep the run's saved params (temperature/top_p/...) even when its model is
  // gone and we fall back to another — submit sanitizes what the resolved model
  // can't accept. They used to be silently dropped on any model change.
  const modelConfig = hasExistingConfig
    ? { ...defaultConfig, ...existingConfig }
    : defaultConfig;

  // Leave the algorithm model unset unless the saved run explicitly used a model
  // the workspace can still run. An unset model inherits the prompt model at
  // runtime (surfaced in the picker as "Same as prompt"). We must not
  // force-seed it to the prompt model: that overrode intentional inheritance and
  // could submit a stale/unavailable model behind an "inherited" label.
  const baseOptimizerParams =
    optimization?.studio_config?.optimizer.parameters ||
    getDefaultOptimizerConfig(optimizerType);
  const savedOptimizerModel = (baseOptimizerParams as { model?: string }).model;
  const optimizerParams = {
    ...baseOptimizerParams,
    model:
      savedOptimizerModel && availableModels.includes(savedOptimizerModel)
        ? savedOptimizerModel
        : undefined,
  };

  return {
    name: optimization?.name || "Optimization run",
    datasetId: optimization?.dataset_id || "",
    optimizerType,
    optimizerParams,
    metricType,
    // Merge saved params over the metric defaults so required fields the saved
    // config omitted (e.g. EQUALS `case_sensitive`) are still present. Otherwise
    // Zod rejects the missing field, no inline error renders for it, and Create
    // silently no-ops. Mirrors the modelConfig merge above.
    metricParams: {
      ...getDefaultMetricConfig(metricType),
      ...optimization?.studio_config?.evaluation?.metrics?.[0]?.parameters,
    },
    messages,
    modelName,
    modelConfig,
  } as OptimizationConfigFormType;
};

export const convertFormDataToStudioConfig = (
  formData: OptimizationConfigFormType,
  datasetName: string,
): OptimizationStudioConfig => {
  const messages = formData.messages.map((m) => ({
    role: m.role,
    content:
      typeof m.content === "string" ? m.content : JSON.stringify(m.content),
  }));

  return {
    dataset_name: datasetName,
    prompt: {
      messages,
    },
    llm_model: {
      model: formData.modelName,
      // Drop params the resolved model doesn't accept (e.g. temperature) before
      // the gateway rejects them — same hardening the playground applies. The
      // cast is only because sanitizeConfigForRequest types its arg as the legacy enum.
      parameters: sanitizeConfigForRequest(
        formData.modelName as PROVIDER_MODEL_TYPE,
        formData.modelConfig,
      ),
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
