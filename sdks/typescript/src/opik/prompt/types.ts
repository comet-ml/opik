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
 * Configuration options for creating a new prompt
 * Extends REST API PromptWrite with renamed 'prompt' field
 */
export interface CreatePromptOptions {
  /** Name of the prompt (unique identifier) */
  name: string;
  /** Template text content with placeholders */
  prompt: string;
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
  createdAt?: Date;
  createdBy?: string;
}
