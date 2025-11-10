import { DropdownOption } from "@/types/shared";

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

export interface LLMJudgeSchema {
  name: string;
  type: LLM_SCHEMA_TYPE;
  description: string;
  unsaved: boolean;
}

export interface LLMMessage {
  id: string;
  content: string;
  role: LLM_MESSAGE_ROLE;
  promptId?: string;
  promptVersionId?: string;
  autoImprove?: boolean;
}

export type ProviderMessageType = Omit<LLMMessage, "id">;

export enum LLM_JUDGE {
  custom = "custom",
  hallucination = "hallucination",
  moderation = "moderation",
  answer_relevance = "answer_relevance",
  context_precision = "context_precision",
  syceval = "syceval",
  structure_compliance = "structure_compliance",
  conversational_coherence = "conversational_coherence",
  session_completeness = "session_completeness",
  user_frustration = "user_frustration",
}

export type LLMPromptTemplate = {
  messages: LLMMessage[];
  variables: Record<string, string>;
  schema: LLMJudgeSchema[];
} & DropdownOption<LLM_JUDGE>;

export type ScoresValidationError =
  | {
      name?: {
        message: string;
      };
      unsaved?: {
        message: string;
      };
    }[]
  | {
      message: string;
    };
