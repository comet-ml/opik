import { LLMAsJudgeData, LLMJudgeObject } from "@/types/automations";
import { LLM_JUDGE } from "@/types/llm";
import {
  convertLLMToProviderMessages,
  convertProviderToLLMMessages,
} from "@/lib/llm";

export const convertLLMJudgeObjectToLLMJudgeData = (data: LLMJudgeObject) => {
  return {
    model: data.model.name,
    config: {
      temperature: data.model.temperature,
    },
    template: LLM_JUDGE.custom, // TODO lala not defined
    messages: convertProviderToLLMMessages(data.messages),
    variables: data.variables,
    schema: data.schema,
  };
};

export const convertLLMJudgeDataToLLMJudgeObject = (data: LLMAsJudgeData) => {
  return {
    config: {
      name: data.model,
      temperature: data.config.temperature,
    },
    // template: LLM_JUDGE.custom, // TODO lala not defined
    messages: convertLLMToProviderMessages(data.messages),
    variables: data.variables,
    schema: data.schema,
  };
};
