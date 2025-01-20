export enum PROVIDER_TYPE {
  OPEN_AI = "openai",
  ANTHROPIC = "anthropic",
  GEMINI = "gemini",
}

export enum PROVIDER_MODEL_TYPE {
  // <------ openai
  GPT_4O = "gpt-4o",
  GPT_4O_MINI = "gpt-4o-mini",
  GPT_4O_MINI_2024_07_18 = "gpt-4o-mini-2024-07-18",
  GPT_4O_2024_08_06 = "gpt-4o-2024-08-06",
  GPT_4O_2024_05_13 = "gpt-4o-2024-05-13",
  GPT_4_TURBO = "gpt-4-turbo",
  GPT_4 = "gpt-4",
  GPT_4_TURBO_PREVIEW = "gpt-4-turbo-preview",
  GPT_4_TURBO_2024_04_09 = "gpt-4-turbo-2024-04-09",
  GPT_4_1106_PREVIEW = "gpt-4-1106-preview",
  GPT_4_0613 = "gpt-4-0613",
  GPT_4_0125_PREVIEW = "gpt-4-0125-preview",
  GPT_3_5_TURBO = "gpt-3.5-turbo",
  GPT_3_5_TURBO_1106 = "gpt-3.5-turbo-1106",
  GPT_3_5_TURBO_0125 = "gpt-3.5-turbo-0125",

  //  <----- anthropic
  CLAUDE_3_5_SONNET_LATEST = "claude-3-5-sonnet-latest",
  CLAUDE_3_5_SONNET_20241022 = "claude-3-5-sonnet-20241022",
  CLAUDE_3_5_HAIKU_LATEST = "claude-3-5-haiku-latest",
  CLAUDE_3_5_HAIKU_20241022 = "claude-3-5-haiku-20241022",
  CLAUDE_3_5_SONNET_20240620 = "claude-3-5-sonnet-20240620",
  CLAUDE_3_OPUS_LATEST = "claude-3-opus-latest",
  CLAUDE_3_OPUS_20240229 = "claude-3-opus-20240229",
  CLAUDE_3_SONNET_20240229 = "claude-3-sonnet-20240229",
  CLAUDE_3_HAIKU_20240307 = "claude-3-haiku-20240307",

  //  <----- gemini
  GEMINI_2_0_FLASH = "gemini-2.0-flash-exp",
  GEMINI_1_5_FLASH = "gemini-1.5-flash",
  GEMINI_1_5_FLASH_8B = "gemini-1.5-flash-8b",
  GEMINI_1_5_PRO = "gemini-1.5-pro",
  GEMINI_1_0_PRO = "gemini-1.0-pro",
  TEXT_EMBEDDING = "text-embedding-004",
  AQA = "aqa",
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

export interface LLMAnthropicConfigsType {
  temperature: number;
  maxCompletionTokens: number;
  topP: number;
}

export interface LLMGeminiConfigsType {
  temperature: number;
  maxCompletionTokens: number;
  topP: number;
}

export type LLMPromptConfigsType =
  | Record<string, never>
  | LLMOpenAIConfigsType
  | LLMAnthropicConfigsType
  | LLMGeminiConfigsType;
