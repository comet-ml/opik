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
import {
  getMessageContentTextSegments,
  isMessageContentEmpty,
} from "@/lib/llm";

const RuleNameSchema = z
  .string({
    required_error: "Rule name is required",
  })
  .min(1, { message: "Rule name is required" });

const ProjectIdSchema = z
  .string({
    required_error: "Project is required",
  })
  .min(1, { message: "Project is required" });

const SamplingRateSchema = z.number();

const ScopeSchema = z.nativeEnum(EVALUATORS_RULE_SCOPE);

const TextMessageContentSchema = z.object({
  type: z.literal("text"),
  text: z.string(),
});

const ImageMessageContentSchema = z.object({
  type: z.literal("image_url"),
  image_url: z.object({
    url: z.string().min(1, { message: "Image URL is required" }),
    detail: z.string().optional(),
  }),
});

const StructuredMessageContentSchema = z.array(
  z.union([TextMessageContentSchema, ImageMessageContentSchema]),
);

const MessageContentSchema = z
  .union([z.string(), StructuredMessageContentSchema])
  .refine((value) => !isMessageContentEmpty(value as never), {
    message: "Message is required",
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
  }),
  template: z.nativeEnum(LLM_JUDGE),
  messages: z.array(
    z.object({
      id: z.string(),
      content: MessageContentSchema,
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
      .regex(/^(input|output|metadata)/, {
        message: `Key is invalid, it should begin with "input", "output", or "metadata" and follow this format: "input.[PATH]" For example: "input.message"`,
      }),
  ),
});

export const LLMJudgeDetailsThreadFormSchema = LLMJudgeBaseSchema.extend({
  variables: z.record(z.string(), z.string()),
}).superRefine((data, ctx) => {
  const contextCount = data.messages.filter((m) => {
    const segments = getMessageContentTextSegments(m.content);
    return segments.some((segment) => segment.includes("{{context}}"));
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
    const segments = getMessageContentTextSegments(message.content);
    segments.forEach((segment) => {
      const matches = segment.match(/{{([^}]+)}}/g);
      if (!matches) {
        return;
      }

      matches.forEach((match) => {
        if (match !== "{{context}}") {
          ctx.addIssue({
            code: z.ZodIssueCode.custom,
            message: `Template variable ${match} is not allowed. Only {{context}} is supported.`,
            path: ["messages", index, "content"],
          });
        }
      });
    });
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
    arguments: z.record(
      z.string(),
      z
        .string()
        .min(1, { message: "Key is required" })
        .regex(/^(input|output|metadata)/, {
          message: `Key is invalid, it should begin with "input", "output", or "metadata" and follow this format: "input.[PATH]" For example: "input.message"`,
        }),
    ),
    parsingArgumentsError: z.boolean().optional(),
  },
);

export const PythonCodeDetailsThreadFormSchema = BasePythonCodeFormSchema;

export const BaseEvaluationRuleFormSchema = z.object({
  ruleName: RuleNameSchema,
  projectId: ProjectIdSchema,
  samplingRate: SamplingRateSchema,
  scope: ScopeSchema,
  uiType: z.nativeEnum(UI_EVALUATORS_RULE_TYPE),
  enabled: z.boolean().default(true),
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

export const EvaluationRuleFormSchema = z.discriminatedUnion("type", [
  LLMJudgeTraceEvaluationRuleFormSchema,
  LLMJudgeThreadEvaluationRuleFormSchema,
  PythonCodeTraceEvaluationRuleFormSchema,
  PythonCodeThreadEvaluationRuleFormSchema,
]);

export type LLMJudgeDetailsTraceFormType = z.infer<
  typeof LLMJudgeDetailsTraceFormSchema
>;

export type LLMJudgeDetailsThreadFormType = z.infer<
  typeof LLMJudgeDetailsThreadFormSchema
>;

export type EvaluationRuleFormType = z.infer<typeof EvaluationRuleFormSchema>;

const convertLLMToProviderMessages = (messages: LLMMessage[]) =>
  messages.map((m) => ({ content: m.content, role: m.role.toUpperCase() }));

/**
 * Deserialize message content from string to structured format.
 * Converts `<<<image>>>URL<<</image>>>` placeholders back to structured content array.
 */
const deserializeMessageContent = (
  content: string | any[],
): string | any[] => {
  if (typeof content !== "string") {
    return content;
  }

  // Check if content contains image placeholders
  const imagePattern = /<<<image>>>(.+?)<<\/image>>>/g;
  if (!imagePattern.test(content)) {
    return content;
  }

  // Parse into structured content array
  const parts: any[] = [];
  let lastIndex = 0;
  imagePattern.lastIndex = 0; // Reset regex state

  let match;
  while ((match = imagePattern.exec(content)) !== null) {
    // Add text before the image placeholder
    if (match.index > lastIndex) {
      const textSegment = content.substring(lastIndex, match.index);
      if (textSegment) {
        parts.push({
          type: "text",
          text: textSegment,
        });
      }
    }

    // Add image content
    const imageUrl = match[1];
    parts.push({
      type: "image_url",
      image_url: {
        url: imageUrl,
      },
    });

    lastIndex = match.index + match[0].length;
  }

  // Add remaining text after last image
  if (lastIndex < content.length) {
    const trailingText = content.substring(lastIndex);
    if (trailingText) {
      parts.push({
        type: "text",
        text: trailingText,
      });
    }
  }

  return parts.length > 0 ? parts : content;
};

const convertProviderToLLMMessages = (messages: ProviderMessageType[]) =>
  messages.map(
    (m) =>
      ({
        ...m,
        content: deserializeMessageContent(m.content),
        role: m.role.toLowerCase(),
        id: generateRandomString(),
      }) as LLMMessage,
  );

export const convertLLMJudgeObjectToLLMJudgeData = (data: LLMJudgeObject) => {
  return {
    model: data.model?.name ?? "",
    config: {
      temperature: data.model?.temperature ?? 0,
      seed: data.model?.seed ?? null,
    },
    template: LLM_JUDGE.custom,
    messages: convertProviderToLLMMessages(data.messages),
    variables: data.variables ?? {},
    parsingVariablesError: false,
    schema: data.schema,
  };
};

export const convertLLMJudgeDataToLLMJudgeObject = (
  data: LLMJudgeDetailsTraceFormType | LLMJudgeDetailsThreadFormType,
) => {
  const { temperature, seed } = data.config;
  const model: LLMJudgeObject["model"] = {
    name: data.model as PROVIDER_MODEL_TYPE,
    temperature,
  };

  if (seed != null) {
    model.seed = seed;
  }

  return {
    model,
    messages: convertLLMToProviderMessages(data.messages),
    variables: data.variables,
    schema: data.schema,
  };
};
