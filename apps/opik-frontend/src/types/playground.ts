import { UsageType } from "@/types/shared";
import { LLMMessage } from "@/types/llm";
import { HttpStatusCode } from "axios";
import { LLMPromptConfigsType, PROVIDER_MODEL_TYPE } from "@/types/providers";

export interface PlaygroundPromptType {
  name: string;
  id: string;
  messages: LLMMessage[];
  model: PROVIDER_MODEL_TYPE | "";
  configs: LLMPromptConfigsType;
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
