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

export type PlaygroundOutputType = string | null;

export interface PlaygroundPromptType {
  name: string;
  id: string;
  messages: PlaygroundMessageType[];
  model: PROVIDER_MODEL_TYPE | "";
  configs: PlaygroundPromptConfigsType;
  output: PlaygroundOutputType;
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

export interface ChatCompletionProviderErrorMessageType {
  code: HttpStatusCode;
  message: string;
}

export type ChatCompletionOpikErrorMessageType =
  | {
      errors: string[];
    }
  | {
      message: string;
      code: string;
    };

export type ChatCompletionResponse =
  | ChatCompletionOpikErrorMessageType
  | ChatCompletionSuccessMessageType
  | ChatCompletionProviderErrorMessageType;
