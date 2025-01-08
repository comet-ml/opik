import { LLM_JUDGE } from "@/types/llm";
import { LLMAsJudgeData } from "@/types/automations";
import { LLM_PROMPT_CUSTOM_TEMPLATE } from "@/constants/llm";

export const DEFAULT_SAMPLING_RATE = 1;

export const DEFAULT_LLM_AS_JUDGE_DATA: LLMAsJudgeData = {
  model: "",
  config: {
    temperature: 0.0,
  },
  template: LLM_JUDGE.custom,
  messages: LLM_PROMPT_CUSTOM_TEMPLATE.messages,
  variables: LLM_PROMPT_CUSTOM_TEMPLATE.variables,
  schema: LLM_PROMPT_CUSTOM_TEMPLATE.schema,
};
