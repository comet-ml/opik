import { z } from "zod";
import uniq from "lodash/uniq";
import {
  LLMJudgeObject,
  EVALUATORS_RULE_SCOPE,
  UI_EVALUATORS_RULE_TYPE,
  EVALUATORS_RULE_TYPE,
} from "@/types/automations";
import {
  LLM_JUDGE,
  LLM_MESSAGE_ROLE,
  LLM_SCHEMA_TYPE,
  LLMMessage,
  ProviderMessageType,
} from "@/types/llm";
import { generateRandomString } from "@/lib/utils";

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
      .min(0, { message: "Seed must be a positive integer" })
      .optional()
      .nullable(),
  }),
  template: z.nativeEnum(LLM_JUDGE),
  messages: z.array(
    z.object({
      id: z.string(),
      content: z.string().min(1, { message: "Message is required" }),
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
  const contextCount = data.messages.filter((m) =>
    m.content.includes("{{context}}"),
  ).length;

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
    const matches = message.content.match(/{{([^}]+)}}/g);
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

const convertProviderToLLMMessages = (messages: ProviderMessageType[]) =>
  messages.map(
    (m) =>
      ({
        ...m,
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
    name: data.model,
    temperature,
  };

  if (seed !== undefined && seed !== null && !Number.isNaN(seed)) {
    model.seed = seed;
  }

  return {
    model,
    messages: convertLLMToProviderMessages(data.messages),
    variables: data.variables,
    schema: data.schema,
  };
};
