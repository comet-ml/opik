import { z } from "zod";
import uniq from "lodash/uniq";
import { EVALUATORS_RULE_TYPE, LLMJudgeObject } from "@/types/automations";
import {
  LLM_JUDGE,
  LLM_MESSAGE_ROLE,
  LLM_SCHEMA_TYPE,
  LLMMessage,
  ProviderMessageType,
} from "@/types/llm";
import { PROVIDER_MODEL_TYPE } from "@/types/providers";
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

export const LLMJudgeDetailsFormSchema = z.object({
  model: z
    .union([z.nativeEnum(PROVIDER_MODEL_TYPE), z.string().length(0)], {
      required_error: "Model is required",
    })
    .refine((model) => model.length >= 1, { message: "Model is required" }),
  config: z.object({
    temperature: z.number(),
  }),
  template: z.nativeEnum(LLM_JUDGE),
  messages: z.array(
    z.object({
      id: z.string(),
      content: z.string().min(1, { message: "Message is required" }),
      role: z.nativeEnum(LLM_MESSAGE_ROLE),
    }),
  ),
  variables: z.record(
    z.string(),
    z
      .string()
      .min(1, { message: "Key is required" })
      .regex(/^(input|output|metadata)/, {
        message: `Key is invalid, it should begin with "input", "output", or "metadata" and follow this format: "input.[PATH]" For example: "input.message"`,
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

// TODO lala check. as well partial validation
export const PythonCodeDetailsFormSchema = z.object({
  metric: z
    .string({
      required_error: "Code is required",
    })
    .min(1, { message: "Code is required" }),
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
});

export const LLMEvaluationRuleFormSchema = z.object({
  ruleName: RuleNameSchema,
  projectId: ProjectIdSchema,
  samplingRate: SamplingRateSchema,
  type: z.literal(EVALUATORS_RULE_TYPE.llm_judge),
  llmJudgeDetails: LLMJudgeDetailsFormSchema,
});

export const PythonEvaluationRuleFormSchema = z.object({
  ruleName: RuleNameSchema,
  projectId: ProjectIdSchema,
  samplingRate: SamplingRateSchema,
  type: z.literal(EVALUATORS_RULE_TYPE.python_code),
  pythonCodeDetails: PythonCodeDetailsFormSchema,
});

export const EvaluationRuleFormSchema = z.discriminatedUnion("type", [
  LLMEvaluationRuleFormSchema,
  PythonEvaluationRuleFormSchema,
]);

export type LLMJudgeDetailsFormType = z.infer<typeof LLMJudgeDetailsFormSchema>;

export type PythonCodeDetailsFormType = z.infer<
  typeof PythonCodeDetailsFormSchema
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
    },
    template: LLM_JUDGE.custom,
    messages: convertProviderToLLMMessages(data.messages),
    variables: data.variables,
    parsingVariablesError: false,
    schema: data.schema,
  };
};

export const convertLLMJudgeDataToLLMJudgeObject = (
  data: LLMJudgeDetailsFormType,
) => {
  return {
    model: {
      name: data.model,
      temperature: data.config.temperature,
    },
    messages: convertLLMToProviderMessages(data.messages),
    variables: data.variables,
    schema: data.schema,
  };
};
