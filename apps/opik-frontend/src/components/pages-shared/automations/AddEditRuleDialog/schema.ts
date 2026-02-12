import { z } from "zod";
import uniq from "lodash/uniq";
import {
  LLMJudgeObject,
  EVALUATORS_RULE_SCOPE,
  UI_EVALUATORS_RULE_TYPE,
  EVALUATORS_RULE_TYPE,
} from "@/types/automations";
import { PROVIDER_MODEL_TYPE } from "@/types/providers";
import {
  LLM_JUDGE,
  LLM_MESSAGE_ROLE,
  LLM_SCHEMA_TYPE,
  LLMMessage,
  ProviderMessageType,
} from "@/types/llm";
import { generateRandomString } from "@/lib/utils";
import { COLUMN_TYPE } from "@/types/shared";
import {
  supportsImageInput,
  supportsVideoInput,
} from "@/lib/modelCapabilities";
import {
  hasImagesInContent,
  getTextFromMessageContent,
  hasVideosInContent,
} from "@/lib/llm";

const RuleNameSchema = z
  .string({
    required_error: "Rule name is required",
  })
  .min(1, { message: "Rule name is required" });

const ProjectIdsSchema = z
  .array(z.string())
  .min(1, { message: "At least one project is required" });

const SamplingRateSchema = z.number();

const ScopeSchema = z.nativeEnum(EVALUATORS_RULE_SCOPE);

export const FilterSchema = z.object({
  id: z.string(),
  field: z.string(),
  type: z.nativeEnum(COLUMN_TYPE).or(z.literal("")),
  operator: z.union([
    z.literal("contains"),
    z.literal("not_contains"),
    z.literal("starts_with"),
    z.literal("ends_with"),
    z.literal("is_empty"),
    z.literal("is_not_empty"),
    z.literal("="),
    z.literal(">"),
    z.literal(">="),
    z.literal("<"),
    z.literal("<="),
    z.literal(""),
  ]),
  key: z.string().optional(),
  value: z.union([z.string(), z.number()]),
  error: z.string().optional(),
});

export const FiltersSchema = z
  .array(FilterSchema)
  .superRefine((filters, ctx) => {
    filters.forEach((filter, index) => {
      // Validate field
      if (!filter.field || filter.field.trim().length === 0) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: "Field is required",
          path: [index, "field"],
        });
      }

      // Validate operator
      if (!filter.operator || filter.operator.trim().length === 0) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: "Operator is required",
          path: [index, "operator"],
        });
      }

      // Validate value (only for operators that require it)
      if (
        filter.operator &&
        filter.operator !== "is_empty" &&
        filter.operator !== "is_not_empty"
      ) {
        const valueString = String(filter.value || "").trim();
        if (valueString.length === 0) {
          ctx.addIssue({
            code: z.ZodIssueCode.custom,
            message: "Value is required for this operator",
            path: [index, "value"],
          });
        }
      }

      // Validate key for dictionary types
      if (
        (filter.type === COLUMN_TYPE.dictionary ||
          filter.type === COLUMN_TYPE.numberDictionary) &&
        (!filter.key || filter.key.trim().length === 0)
      ) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: "Key is required for dictionary fields",
          path: [index, "key"],
        });
      }

      // Add custom error if present
      if (filter.error) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: filter.error,
          path: [index, "value"],
        });
      }
    });
  });

const LLMJudgeBaseSchema = z.object({
  model: z
    .string({
      required_error: "Model is required",
    })
    .min(1, { message: "Model is required" }),
  config: z.object({
    temperature: z.number(),
    seed: z
      .number()
      .int()
      .min(0, { message: "Seed must be a non-negative integer" })
      .optional()
      .nullable(),
    custom_parameters: z.record(z.string(), z.unknown()).optional().nullable(),
  }),
  template: z.nativeEnum(LLM_JUDGE),
  messages: z.array(
    z.object({
      id: z.string(),
      content: z.union([
        z.string().min(1, { message: "Message is required" }),
        z
          .array(
            z.union([
              z.object({ type: z.literal("text"), text: z.string() }),
              z.object({
                type: z.literal("image_url"),
                image_url: z.object({ url: z.string() }),
              }),
              z.object({
                type: z.literal("video_url"),
                video_url: z.object({ url: z.string() }),
              }),
              z.object({
                type: z.literal("audio_url"),
                audio_url: z.object({ url: z.string() }),
              }),
            ]),
          )
          .min(1, { message: "Message is required" }),
      ]),
      role: z.nativeEnum(LLM_MESSAGE_ROLE),
    }),
  ),
  parsingVariablesError: z.boolean().optional(),
  schema: z
    .array(
      z.object({
        name: z
          .string()
          .min(1, { message: "Score definition name is required" }),
        type: z.nativeEnum(LLM_SCHEMA_TYPE),
        description: z.string(),
        unsaved: z
          .boolean()
          .optional()
          .default(false)
          .refine((value) => !value, {
            message: "Changes not saved",
          }),
      }),
    )
    .refine(
      (schema) => {
        const schemaNames = schema.map((s) => s.name);

        return schemaNames.length === uniq(schemaNames).length;
      },
      { message: "All score definition names should be unique" },
    ),
});

export const LLMJudgeDetailsTraceFormSchema = LLMJudgeBaseSchema.extend({
  variables: z.record(
    z.string(),
    z
      .string()
      .min(1, { message: "Key is required" })
      .regex(/^(input|output|metadata)(\.|$)/, {
        message: `Key is invalid, it should be "input", "output", "metadata", and follow this format: "input.[PATH]" For example: "input.message" or just "input" for the whole object`,
      }),
  ),
}).superRefine((data, ctx) => {
  const hasImages = data.messages.some((message) =>
    hasImagesInContent(message.content),
  );
  const hasVideos = data.messages.some((message) =>
    hasVideosInContent(message.content),
  );

  if (hasImages || hasVideos) {
    const modelSupportsImages = supportsImageInput(data.model);
    const modelSupportsVideos = supportsVideoInput(data.model);
    const supportsMultimodal = modelSupportsImages || modelSupportsVideos;

    if (!supportsMultimodal) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        message:
          "The selected model does not support media input. Please choose a model with vision capabilities or remove images from messages.",
        path: ["model"],
      });
    } else {
      if (hasImages && !modelSupportsImages) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message:
            "The selected model does not support image input. Please choose a model with vision capabilities or remove images from messages.",
          path: ["model"],
        });
      }
      if (hasVideos && !modelSupportsVideos) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message:
            "The selected model does not support video input. Please choose a model with video capabilities or remove videos from messages.",
          path: ["model"],
        });
      }
    }
  }
});

export const LLMJudgeDetailsSpanFormSchema = LLMJudgeBaseSchema.extend({
  variables: z.record(
    z.string(),
    z
      .string()
      .min(1, { message: "Key is required" })
      .regex(/^(input|output|metadata)(\.|$)/, {
        message: `Key is invalid, it should be "input", "output", "metadata", and follow this format: "input.[PATH]" For example: "input.message" or just "input" for the whole object`,
      }),
  ),
}).superRefine((data, ctx) => {
  const hasImages = data.messages.some((message) =>
    hasImagesInContent(message.content),
  );
  const hasVideos = data.messages.some((message) =>
    hasVideosInContent(message.content),
  );

  if (hasImages || hasVideos) {
    const modelSupportsImages = supportsImageInput(data.model);
    const modelSupportsVideos = supportsVideoInput(data.model);
    const supportsMultimodal = modelSupportsImages || modelSupportsVideos;

    if (!supportsMultimodal) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        message:
          "The selected model does not support media input. Please choose a model with vision capabilities or remove images from messages.",
        path: ["model"],
      });
    } else {
      if (hasImages && !modelSupportsImages) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message:
            "The selected model does not support image input. Please choose a model with vision capabilities or remove images from messages.",
          path: ["model"],
        });
      }
      if (hasVideos && !modelSupportsVideos) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message:
            "The selected model does not support video input. Please choose a model with video capabilities or remove videos from messages.",
          path: ["model"],
        });
      }
    }
  }
});

export const LLMJudgeDetailsThreadFormSchema = LLMJudgeBaseSchema.extend({
  variables: z.record(z.string(), z.string()),
}).superRefine((data, ctx) => {
  const contextCount = data.messages.filter((m) => {
    const content = getTextFromMessageContent(m.content);
    return content.includes("{{context}}");
  }).length;

  if (contextCount < 1) {
    ctx.addIssue({
      code: z.ZodIssueCode.custom,
      message: "At least one message should contain the {{context}} variable",
      path: ["messages", data.messages.length - 1, "content"],
    });
  }

  if (contextCount > 1) {
    ctx.addIssue({
      code: z.ZodIssueCode.custom,
      message: "Only one message can contain the {{context}} variable.",
      path: ["messages", data.messages.length - 1, "content"],
    });
  }

  data.messages.forEach((message, index) => {
    const content = getTextFromMessageContent(message.content);
    const matches = content.match(/{{([^}]+)}}/g);
    if (matches) {
      matches.forEach((match) => {
        if (match !== "{{context}}") {
          ctx.addIssue({
            code: z.ZodIssueCode.custom,
            message: `Template variable ${match} is not allowed. Only {{context}} is supported.`,
            path: ["messages", index, "content"],
          });
        }
      });
    }
  });
});

export const BasePythonCodeFormSchema = z.object({
  metric: z
    .string({
      required_error: "Code is required",
    })
    .min(1, { message: "Code is required" }),
});

export const PythonCodeDetailsTraceFormSchema = BasePythonCodeFormSchema.extend(
  {
    arguments: z
      .record(
        z.string(),
        z
          .string()
          .min(1, { message: "Key is required" })
          .regex(/^(input|output|metadata)(\.|$)/, {
            message: `Key is invalid, it should be "input", "output", "metadata", and follow this format: "input.[PATH]" For example: "input.message" or just "input" for the whole object`,
          }),
      )
      .optional(),
    parsingArgumentsError: z.boolean().optional(),
  },
);

export const PythonCodeDetailsThreadFormSchema = BasePythonCodeFormSchema;

export const PythonCodeDetailsSpanFormSchema = BasePythonCodeFormSchema.extend({
  arguments: z
    .record(
      z.string(),
      z
        .string()
        .min(1, { message: "Key is required" })
        .regex(/^(input|output|metadata)(\.|$)/, {
          message: `Key is invalid, it should be "input", "output", "metadata", and follow this format: "input.[PATH]" For example: "input.message" or just "input" for the whole object`,
        }),
    )
    .optional(),
  parsingArgumentsError: z.boolean().optional(),
});

export const BaseEvaluationRuleFormSchema = z.object({
  ruleName: RuleNameSchema,
  projectIds: ProjectIdsSchema,
  samplingRate: SamplingRateSchema,
  scope: ScopeSchema,
  uiType: z.nativeEnum(UI_EVALUATORS_RULE_TYPE),
  enabled: z.boolean().default(true),
  filters: FiltersSchema.default([]),
});

export const LLMJudgeTraceEvaluationRuleFormSchema =
  BaseEvaluationRuleFormSchema.extend({
    type: z.literal(EVALUATORS_RULE_TYPE.llm_judge),
    llmJudgeDetails: LLMJudgeDetailsTraceFormSchema,
  });

export const LLMJudgeThreadEvaluationRuleFormSchema =
  BaseEvaluationRuleFormSchema.extend({
    type: z.literal(EVALUATORS_RULE_TYPE.thread_llm_judge),
    llmJudgeDetails: LLMJudgeDetailsThreadFormSchema,
  });

export const LLMJudgeSpanEvaluationRuleFormSchema =
  BaseEvaluationRuleFormSchema.extend({
    type: z.literal(EVALUATORS_RULE_TYPE.span_llm_judge),
    llmJudgeDetails: LLMJudgeDetailsSpanFormSchema,
  });

export const PythonCodeTraceEvaluationRuleFormSchema =
  BaseEvaluationRuleFormSchema.extend({
    type: z.literal(EVALUATORS_RULE_TYPE.python_code),
    pythonCodeDetails: PythonCodeDetailsTraceFormSchema,
  });

export const PythonCodeThreadEvaluationRuleFormSchema =
  BaseEvaluationRuleFormSchema.extend({
    type: z.literal(EVALUATORS_RULE_TYPE.thread_python_code),
    pythonCodeDetails: PythonCodeDetailsThreadFormSchema,
  });

export const PythonCodeSpanEvaluationRuleFormSchema =
  BaseEvaluationRuleFormSchema.extend({
    type: z.literal(EVALUATORS_RULE_TYPE.span_python_code),
    pythonCodeDetails: PythonCodeDetailsSpanFormSchema,
  });

export const EvaluationRuleFormSchema = z.discriminatedUnion("type", [
  LLMJudgeTraceEvaluationRuleFormSchema,
  LLMJudgeThreadEvaluationRuleFormSchema,
  LLMJudgeSpanEvaluationRuleFormSchema,
  PythonCodeTraceEvaluationRuleFormSchema,
  PythonCodeThreadEvaluationRuleFormSchema,
  PythonCodeSpanEvaluationRuleFormSchema,
]);

export type LLMJudgeDetailsTraceFormType = z.infer<
  typeof LLMJudgeDetailsTraceFormSchema
>;

export type LLMJudgeDetailsThreadFormType = z.infer<
  typeof LLMJudgeDetailsThreadFormSchema
>;

export type LLMJudgeDetailsSpanFormType = z.infer<
  typeof LLMJudgeDetailsSpanFormSchema
>;

export type EvaluationRuleFormType = z.infer<typeof EvaluationRuleFormSchema>;

const convertLLMToProviderMessages = (
  messages: LLMMessage[],
): ProviderMessageType[] =>
  messages.map(({ content, ...rest }) => {
    const base: ProviderMessageType = {
      ...rest,
      content,
      role: rest.role.toUpperCase() as LLM_MESSAGE_ROLE,
    };

    // For LlmAsJudgeMessage (online scoring), use separate fields
    // Only set the appropriate field based on content type
    if (typeof content === "string") {
      return { ...base, content };
    } else if (Array.isArray(content)) {
      return {
        ...base,
        content: null,
        content_array: content,
      } as unknown as ProviderMessageType;
    }

    return base;
  });

const convertProviderToLLMMessages = (
  messages: ProviderMessageType[],
): LLMMessage[] =>
  messages.map((m) => ({
    ...m,
    role: m.role.toLowerCase() as LLM_MESSAGE_ROLE,
    // Convert from separate fields to union type for frontend
    content: m.content_array ?? m.content ?? "",
    id: generateRandomString(),
  }));

export const convertLLMJudgeObjectToLLMJudgeData = (data: LLMJudgeObject) => {
  return {
    model: data.model?.name ?? "",
    config: {
      temperature: data.model?.temperature ?? 0,
      seed: data.model?.seed ?? null,
      custom_parameters: data.model?.custom_parameters ?? null,
    },
    template: LLM_JUDGE.custom,
    messages: convertProviderToLLMMessages(data.messages),
    variables: data.variables ?? {},
    parsingVariablesError: false,
    schema: data.schema,
  };
};

export const convertLLMJudgeDataToLLMJudgeObject = (
  data:
    | LLMJudgeDetailsTraceFormType
    | LLMJudgeDetailsThreadFormType
    | LLMJudgeDetailsSpanFormType,
  options?: { skipVariables?: boolean },
) => {
  const { temperature, seed, custom_parameters } = data.config;
  const model: LLMJudgeObject["model"] = {
    name: data.model as PROVIDER_MODEL_TYPE,
    temperature,
  };

  if (seed != null) {
    model.seed = seed;
  }

  if (custom_parameters != null) {
    model.custom_parameters = custom_parameters;
  }

  const variables = options?.skipVariables ? {} : data.variables;

  return {
    model,
    messages: convertLLMToProviderMessages(data.messages),
    variables,
    schema: data.schema,
  };
};
