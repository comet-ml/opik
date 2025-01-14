import { LLMAsJudgeData, LLMJudgeObject } from "@/types/automations";
import { LLM_JUDGE } from "@/types/llm";
import {
  convertLLMToProviderMessages,
  convertProviderToLLMMessages,
} from "@/lib/llm";
import { PROVIDER_MODEL_TYPE } from "@/types/providers";

export const convertLLMJudgeObjectToLLMJudgeData = (data: LLMJudgeObject) => {
  // TODO lala workaround
  if (!data.model) {
    data.model = {
      name: PROVIDER_MODEL_TYPE.GPT_4,
      temperature: 0,
    };
  }

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
    // template: LLM_JUDGE.custom,
    messages: convertLLMToProviderMessages(data.messages),
    variables: data.variables,
    schema: data.schema,
  };
};
