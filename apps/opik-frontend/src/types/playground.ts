import { UsageType } from "@/types/shared";
import { HttpStatusCode } from "axios";
import {
  PlaygroundOpenAIConfigsType,
  PROVIDER_MODEL_TYPE,
} from "@/types/providers";

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

export type PlaygroundPromptConfigsType =
  | Record<string, never>
  | PlaygroundOpenAIConfigsType;

export interface PlaygroundPromptType {
  name: string;
  id: string;
  messages: PlaygroundMessageType[];
  model: PROVIDER_MODEL_TYPE | "";
  configs: PlaygroundPromptConfigsType;
}

export interface ProviderMessageType {
  content: string;
  role: PLAYGROUND_MESSAGE_ROLE;
}

export interface ChatCompletionMessageChoiceType {
  delta: {
    content: string;
  };
}

export interface ChatCompletionSuccessMessageType {
  choices: ChatCompletionMessageChoiceType[];
  usage: UsageType;
}

export interface ChatCompletionErrorMessageType {
  code: HttpStatusCode;
  message: string;
}

export interface ChatCompletionProxyErrorMessageType {
  errors: string[];
}

export type ChatCompletionResponse =
  | ChatCompletionProxyErrorMessageType
  | ChatCompletionSuccessMessageType
  | ChatCompletionErrorMessageType;
