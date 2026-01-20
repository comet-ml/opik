import type * as OpikApi from "@/rest_api/api";

/**
 * Supported template engine types for prompts
 * Re-exported from REST API with uppercase values for consistency
 */
export const PromptType = {
  /** Mustache template syntax with {{variable}} placeholders */
  MUSTACHE: "mustache" as const,
  /** Jinja2 template syntax with {% %} blocks and {{ }} variables */
  JINJA2: "jinja2" as const,
} as const;

export type PromptType = (typeof PromptType)[keyof typeof PromptType];

/**
 * Template structure types for prompts
 */
export const PromptTemplateStructure = {
  /** Text-based prompt with a single template string */
  Text: "text" as const,
  /** Chat-based prompt with an array of messages */
  Chat: "chat" as const,
} as const;

export type PromptTemplateStructure =
  (typeof PromptTemplateStructure)[keyof typeof PromptTemplateStructure];

/**
 * Common options shared between text and chat prompts
 * Used internally for prompt creation logic
 */
export interface CommonPromptOptions {
  /** Optional prompt ID (generated if not provided) */
  promptId?: string;
  /** Optional description for the prompt */
  description?: string;
  /** Optional metadata for tracking and filtering */
  metadata?: OpikApi.JsonNodeWrite;
  /** Optional change description for version tracking */
  changeDescription?: string;
  /** Template engine type, defaults to mustache */
  type?: PromptType;
  /** Optional tags for categorization */
  tags?: string[];
}

/**
 * Configuration options for creating a new prompt
 * Extends REST API PromptWrite with renamed 'prompt' field
 */
export interface CreatePromptOptions extends CommonPromptOptions {
  /** Name of the prompt (unique identifier) */
  name: string;
  /** Template text content with placeholders */
  prompt: string;
}

/**
 * Options for retrieving a specific prompt version
 * Re-exported from REST API PromptVersionRetrieveDetail
 */
export type GetPromptOptions = OpikApi.PromptVersionRetrieveDetail;

/**
 * Variables to be substituted into prompt template.
 */
export type PromptVariables = Record<string, unknown>;

/**
 * Data structure for creating a PromptVersion instance
 */
export interface PromptVersionData {
  name: string;
  prompt: string;
  commit: string;
  promptId: string;
  versionId: string;
  type: PromptType;
  metadata?: OpikApi.JsonNode;
  changeDescription?: string;
  tags?: string[];
  createdAt?: Date;
  createdBy?: string;
}

// Chat prompt types

/**
 * Content part for multimodal chat messages
 */
export interface ContentPart {
  type: string;
  [key: string]: unknown;
}

/**
 * Text content part
 */
export interface TextContentPart extends ContentPart {
  type: "text";
  text: string;
}

/**
 * Image URL content part
 */
export interface ImageUrlContentPart extends ContentPart {
  type: "image_url";
  image_url: {
    url: string;
    detail?: string;
    [key: string]: unknown;
  };
}

/**
 * Video URL content part
 */
export interface VideoUrlContentPart extends ContentPart {
  type: "video_url";
  video_url: {
    url: string;
    mime_type?: string;
    duration?: number;
    format?: string;
    detail?: string;
    [key: string]: unknown;
  };
}

/**
 * Message content can be a string or array of content parts
 */
export type MessageContent = string | ContentPart[];

/**
 * Chat message with role and content
 */
export interface ChatMessage {
  role: string;
  content: MessageContent;
}

/**
 * Modality name for supported content types
 */
export type ModalityName = "vision" | "video";

/**
 * Mapping of modalities to whether they are supported
 */
export type SupportedModalities = Partial<Record<ModalityName, boolean>>;

/**
 * Configuration options for creating a new chat prompt
 */
export interface CreateChatPromptOptions extends CommonPromptOptions {
  /** Name of the prompt (unique identifier) */
  name: string;
  /** Array of chat messages with role and content */
  messages: ChatMessage[];
  /** Whether to validate template placeholders */
  validatePlaceholders?: boolean;
}
