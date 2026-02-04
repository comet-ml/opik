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

export type TextPart = { type: "text"; text: string };
export type ImagePart = { type: "image_url"; image_url: { url: string } };
export type VideoPart = { type: "video_url"; video_url: { url: string } };
export type AudioPart = { type: "audio_url"; audio_url: { url: string } };
export type MessageContent =
  | string
  | Array<TextPart | ImagePart | VideoPart | AudioPart>;

export interface LLMMessage {
  id: string;
  content: MessageContent;
  role: LLM_MESSAGE_ROLE;
  promptId?: string;
  promptVersionId?: string;
  autoImprove?: boolean;
}

export type ProviderMessageType = Omit<LLMMessage, "id"> & {
  content_array?: Array<TextPart | ImagePart | VideoPart | AudioPart> | null;
};

export interface PlaygroundPromptMetadata {
  created_from: "opik_ui";
  type: "messages_json";
  [key: string]: unknown; // Allow additional metadata fields
}

export enum LLM_JUDGE {
  custom = "custom",
  hallucination = "hallucination",
  moderation = "moderation",
  answer_relevance = "answer_relevance",
  context_precision = "context_precision",
  structure_compliance = "structure_compliance",
  conversational_coherence = "conversational_coherence",
  session_completeness = "session_completeness",
  user_frustration = "user_frustration",
  meaning_match = "meaning_match",
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
