import { LLMJudgeSchema } from "@/types/automations";
import { DropdownOption } from "@/types/shared";
import {
  LLMPromptConfigsType,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";

export enum LLM_MESSAGE_ROLE {
  system = "system",
  assistant = "assistant",
  user = "user",
  ai = "ai",
  tool_execution_result = "tool_execution_result",
}

export enum LLM_SCHEMA_TYPE {
  INTEGER = "INTEGER",
  DOUBLE = "DOUBLE",
  BOOLEAN = "BOOLEAN",
}

export interface LLMMessage {
  id: string;
  content: string;
  role: LLM_MESSAGE_ROLE;
  promptId?: string;
}

export type ProviderMessageType = Omit<LLMMessage, "id">;

export enum LLM_JUDGE {
  custom = "custom",
  hallucination = "hallucination",
  moderation = "moderation",
  answer_relevance = "answer_relevance",
  context_precision = "context_precision",
}

export type LLMPromptTemplate = {
  messages: LLMMessage[];
  variables: Record<string, string>;
  schema: LLMJudgeSchema[];
} & DropdownOption<LLM_JUDGE>;

export type ChatLLMessage = LLMMessage & {
  isLoading?: boolean;
};

export interface LLMChatType {
  value: string;
  messages: ChatLLMessage[];
  model: PROVIDER_MODEL_TYPE | "";
  provider: PROVIDER_TYPE | "";
  configs: LLMPromptConfigsType;
}
