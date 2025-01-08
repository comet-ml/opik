export enum PROVIDER_TYPE {
  OPEN_AI = "openai",
}

export enum PROVIDER_MODEL_TYPE {
  // GPT-4.0 Models
  GPT_4O = "gpt-4o",
  GPT_4O_MINI = "gpt-4o-mini",
  GPT_4O_MINI_2024_07_18 = "gpt-4o-mini-2024-07-18",
  GPT_4O_2024_08_06 = "gpt-4o-2024-08-06",
  GPT_4O_2024_05_13 = "gpt-4o-2024-05-13",

  // GPT-4 Models
  GPT_4_TURBO = "gpt-4-turbo",
  GPT_4 = "gpt-4",
  GPT_4_TURBO_PREVIEW = "gpt-4-turbo-preview",
  GPT_4_TURBO_2024_04_09 = "gpt-4-turbo-2024-04-09",
  GPT_4_1106_PREVIEW = "gpt-4-1106-preview",
  GPT_4_0613 = "gpt-4-0613",
  GPT_4_0125_PREVIEW = "gpt-4-0125-preview",

  // GPT-3.5 Models
  GPT_3_5_TURBO = "gpt-3.5-turbo",
  GPT_3_5_TURBO_1106 = "gpt-3.5-turbo-1106",
  GPT_3_5_TURBO_0125 = "gpt-3.5-turbo-0125",
}

export interface ProviderKey {
  id: string;
  keyName: string;
  created_at: string;
  provider: PROVIDER_TYPE;
}

export interface ProviderKeyWithAPIKey extends ProviderKey {
  apiKey: string;
}

export interface LLMOpenAIConfigsType {
  temperature: number;
  maxCompletionTokens: number;
  topP: number;
  frequencyPenalty: number;
  presencePenalty: number;
}

export type LLMPromptConfigsType = Record<string, never> | LLMOpenAIConfigsType;
