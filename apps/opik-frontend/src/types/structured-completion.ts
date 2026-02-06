import { z } from "zod";
import { PROVIDER_MODEL_TYPE, LLMPromptConfigsType } from "@/types/providers";
import { UsageType } from "@/types/shared";

export interface StructuredCompletionMessage {
  role: "system" | "user" | "assistant";
  content: string;
}

/**
 * Tool call information stored in chat messages
 */
export interface ChatToolCall {
  id: string;
  name: string;
  arguments: Record<string, unknown>;
}

/**
 * Tool result status after user interaction
 */
export type ChatToolResultStatus = "accepted" | "rejected";

export interface ChatDisplayMessage {
  id: string;
  role: "user" | "assistant";
  content: string;
  isLoading?: boolean;
  isError?: boolean;
  /** Tool call data if this message contains a tool call */
  toolCall?: ChatToolCall;
  /** Tool result after user accepts/rejects a proposal */
  toolResultStatus?: ChatToolResultStatus;
}

export interface UseStructuredCompletionParams<T extends z.ZodSchema> {
  schema: T;
  workspaceName: string;
}

/**
 * Context data that can be injected into prompts via {{variableName}} syntax.
 * Similar to how backend OnlineScoringEngine handles trace/span context.
 */
export type ContextData = Record<string, unknown>;

export interface GenerateParams {
  model: PROVIDER_MODEL_TYPE | string;
  /** User message - can contain {{variable}} placeholders */
  userMessage: string;
  /** System prompt - can contain {{variable}} placeholders */
  systemPrompt?: string;
  /** Context data to inject into templates (e.g., trace, span, custom data) */
  context?: ContextData;
  /** Maximum number of tokens to generate (defaults to 4000) */
  maxCompletionTokens?: number;
  /** Model configuration parameters (temperature, top_p, etc.) */
  configs?: Partial<LLMPromptConfigsType>;
}

export interface UseStructuredCompletionReturn<T> {
  /** The parsed and validated structured result */
  result: T | null;
  /** Raw message history (assistant messages contain raw JSON strings) */
  messages: StructuredCompletionMessage[];
  /** Loading state */
  isLoading: boolean;
  /** Error message if any */
  error: string | null;
  /** Token usage from the API */
  usage: UsageType | null;
  /** Generate a new completion with context */
  generate: (params: GenerateParams) => Promise<T | null>;
  /** Manually add a message to history */
  addMessage: (message: StructuredCompletionMessage) => void;
  /** Clear all messages */
  clearMessages: () => void;
  /** Reset entire state */
  reset: () => void;
}
