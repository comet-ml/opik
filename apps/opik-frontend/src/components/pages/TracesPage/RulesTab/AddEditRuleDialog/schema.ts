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

export const EvaluationRuleFormSchema = z.object({
  ruleName: z
    .string({
      required_error: "Rule name is required",
    })
    .min(1, { message: "Rule name is required" }),
  samplingRate: z.number(),
  type: z.nativeEnum(EVALUATORS_RULE_TYPE),
  llmJudgeDetails: LLMJudgeDetailsFormSchema,
  pythonCodeDetails: z.object({
    code: z.string({
      required_error: "Code is required",
    }),
  }),
});

export type LLMJudgeDetailsFormType = z.infer<typeof LLMJudgeDetailsFormSchema>;

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
