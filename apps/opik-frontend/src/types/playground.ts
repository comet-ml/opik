import { HttpStatusCode } from "axios";

import { JsonNode, UsageType } from "@/types/shared";
import { LLMMessage, ProviderMessageType } from "@/types/llm";
import { LLMPromptConfigsType, PROVIDER_MODEL_TYPE } from "@/types/providers";
import { SPAN_TYPE } from "@/types/traces";

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

export interface LogTrace {
  id: string;
  projectName: string;
  name: string;
  startTime: string;
  endTime: string;
  input: { messages: ProviderMessageType[] };
  output: { output: string | null };
}

export interface LogSpan {
  id: string;
  traceId: string;
  projectName: string;
  type: SPAN_TYPE.llm;
  name: string;
  startTime: string;
  endTime: string;
  input: { messages: ProviderMessageType[] };
  output: { choices: ChatCompletionMessageChoiceType[] };
  usage?: UsageType | null;
  metadata: {
    created_from: string;
    usage: UsageType | null;
    model: string;
    parameters: LLMPromptConfigsType;
  };
}

export interface LogExperiment {
  id: string;
  datasetName: string;
  name?: string;
  metadata?: object;
}

export type LogExperimentItem = {
  id: string;
  experimentId: string;
  datasetItemId: string;
  traceId: string;
} & {
  [inputOutputField: string]: JsonNode;
};
