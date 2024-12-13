// ALEX CHANGE THE NAMES
import { UsageType } from "@/types/shared";

export enum PLAYGROUND_PROVIDERS_TYPES {
  "OpenAI" = "OpenAI",
}

export enum PLAYGROUND_MODEL_TYPE {
  "gpt-4o" = "gpt-4o",
  "gpt-4o-mini" = "gpt-4o-mini",
  "gpt-4o-2024-11-20" = "gpt-4o-2024-11-20",
  "gpt-4o-2024-08-06" = "gpt-4o-2024-08-06",
  "gpt-4o-2024-05-13" = "gpt-4o-2024-05-13",
  "gpt-4-turbo" = "gpt-4-turbo",
  "gpt-4" = "gpt-4",
  "gpt-3.5-turbo" = "gpt-3.5-turbo",
  "chatgpt-4o-latest" = "chatgpt-4o-latest",
  "gpt-4o-mini-2024-07-18" = "gpt-4o-mini-2024-07-18",
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
  model: PLAYGROUND_MODEL_TYPE | "";
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
