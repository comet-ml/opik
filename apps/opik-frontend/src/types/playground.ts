import { UsageType } from "@/types/shared";

export enum PLAYGROUND_PROVIDER {
  "OpenAI" = "OpenAI",
}

export enum PLAYGROUND_MODEL {
  // Reasoning Models
  O1_PREVIEW = "o1-preview",
  O1_MINI = "o1-mini",
  O1_MINI_2024_09_12 = "o1-mini-2024-09-12",
  O1_PREVIEW_2024_09_12 = "o1-preview-2024-09-12",

  // GPT-4.0 Models
  GPT_4O = "gpt-4o",
  GPT_4O_MINI = "gpt-4o-mini",
  GPT_4O_MINI_2024_07_18 = "gpt-4o-mini-2024-07-18",
  GPT_4O_2024_11_20 = "gpt-4o-2024-11-20",
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
  GPT_3_5_TURBO_16K = "gpt-3.5-turbo-16k",
  GPT_3_5_TURBO_1106 = "gpt-3.5-turbo-1106",
  GPT_3_5_TURBO_0125 = "gpt-3.5-turbo-0125",

  // Other Models
  CHATGPT_4O_LATEST = "chatgpt-4o-latest",
}

export enum PLAYGROUND_MESSAGE_ROLE {
  system = "system",
  assistant = "assistant",
  user = "user",
}

export interface PlaygroundMessageType {
  content: string;
  id: string;
  role: PLAYGROUND_MESSAGE_ROLE;
}

export interface PlaygroundOpenAIConfigsType {
  temperature: number;
  maxTokens: number;
  topP: number;
  stop: string;
  frequencyPenalty: number;
  presencePenalty: number;
}

export type PlaygroundPromptConfigsType =
  | Record<string, never>
  | PlaygroundOpenAIConfigsType;

export interface PlaygroundPromptType {
  name: string;
  id: string;
  messages: PlaygroundMessageType[];
  model: PLAYGROUND_MODEL | "";
  configs: PlaygroundPromptConfigsType;
}

export interface ProviderMessageType {
  content: string;
  role: PLAYGROUND_MESSAGE_ROLE;
}

export interface ProviderStreamingMessageChoiceType {
  delta: {
    content: string;
  };
}

export interface ProviderStreamingMessageType {
  choices: ProviderStreamingMessageChoiceType[];
  usage: UsageType;
}
