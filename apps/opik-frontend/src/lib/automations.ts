import { LLMAsJudgeData, LLMJudgeObject } from "@/types/automations";
import { LLM_JUDGE } from "@/types/llm";
import {
  convertLLMToProviderMessages,
  convertProviderToLLMMessages,
} from "@/lib/llm";

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

export const convertLLMJudgeDataToLLMJudgeObject = (data: LLMAsJudgeData) => {
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
