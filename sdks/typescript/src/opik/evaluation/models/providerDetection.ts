import { createOpenAI, type OpenAIProviderSettings } from "@ai-sdk/openai";
import { type OpenAIChatModelId } from "@ai-sdk/openai/internal";
import {
  createAnthropic,
  type AnthropicProviderSettings,
} from "@ai-sdk/anthropic";
import { type AnthropicMessagesModelId } from "@ai-sdk/anthropic/internal";
import {
  createGoogleGenerativeAI,
  type GoogleGenerativeAIProviderSettings,
} from "@ai-sdk/google";
import { type GoogleGenerativeAIModelId } from "@ai-sdk/google/internal";
import { type LanguageModel } from "ai";
import { ModelConfigurationError } from "./errors";

/**
 * Union type of all supported model IDs from OpenAI, Anthropic, and Google Gemini.
 *
 * @example
 * ```typescript
 * // Valid model IDs
 * const model1: SupportedModelId = "gpt-5-nano";
 * const model2: SupportedModelId = "claude-3-5-sonnet-latest";
 * const model3: SupportedModelId = "gemini-2.0-flash";
 * ```
 */
export type SupportedModelId =
  | OpenAIChatModelId
  | AnthropicMessagesModelId
  | GoogleGenerativeAIModelId;

/**
 * OpenAI provider-specific options.
 * Extends the official OpenAI provider settings with consistent apiKey support.
 */
export interface OpenAIProviderOptions
  extends Omit<OpenAIProviderSettings, "apiKey"> {
  /** API key for OpenAI. Falls back to OPENAI_API_KEY environment variable if not provided. */
  apiKey?: string;
}

/**
 * Anthropic provider-specific options.
 * Extends the official Anthropic provider settings with consistent apiKey support.
 */
export interface AnthropicProviderOptions
  extends Omit<AnthropicProviderSettings, "apiKey"> {
  /** API key for Anthropic. Falls back to ANTHROPIC_API_KEY environment variable if not provided. */
  apiKey?: string;
}

/**
 * Google Generative AI provider-specific options.
 * Extends the official Google provider settings with consistent apiKey support.
 */
export interface GoogleProviderOptions
  extends Omit<GoogleGenerativeAIProviderSettings, "apiKey"> {
  /** API key for Google. Falls back to GOOGLE_API_KEY environment variable if not provided. */
  apiKey?: string;
}

/**
 * Union type of all possible provider configuration options.
 */
export type AllProviderOptions =
  | OpenAIProviderOptions
  | AnthropicProviderOptions
  | GoogleProviderOptions;

/**
 * Conditional type that maps model ID to the correct provider options.
 * This enables TypeScript to automatically infer the correct options type based on the model ID.
 *
 * @example
 * ```typescript
 * // TypeScript infers OpenAIProviderOptions
 * const options1: ProviderOptionsForModel<"gpt-5-nano"> = {
 *   apiKey: "sk-...",
 *   organization: "org-123" // ✅ Valid OpenAI option
 * };
 *
 * // TypeScript infers AnthropicProviderOptions
 * const options2: ProviderOptionsForModel<"claude-3-5-sonnet-latest"> = {
 *   apiKey: "sk-ant-...",
 * };
 * ```
 */
export type ProviderOptionsForModel<T extends SupportedModelId> =
  T extends OpenAIChatModelId
    ? OpenAIProviderOptions
    : T extends AnthropicMessagesModelId
      ? AnthropicProviderOptions
      : T extends GoogleGenerativeAIModelId
        ? GoogleProviderOptions
        : never;

/**
 * Checks if a model ID matches OpenAI's naming patterns.
 *
 * @param modelId - The model ID to check
 * @returns True if the model ID is an OpenAI model
 */
function isOpenAIModelId(
  modelId: string,
  options?: AllProviderOptions
): options is OpenAIProviderOptions {
  return (
    modelId.startsWith("gpt-") ||
    modelId.startsWith("o1") ||
    modelId.startsWith("o3") ||
    modelId.startsWith("chatgpt-")
  );
}

/**
 * Checks if a model ID matches Anthropic's naming patterns.
 *
 * @param modelId - The model ID to check
 * @returns True if the model ID is an Anthropic model
 */
function isAnthropicModelId(
  modelId: string,
  options?: AllProviderOptions
): options is AnthropicProviderOptions {
  return modelId.startsWith("claude-");
}

/**
 * Checks if a model ID matches Google Gemini's naming patterns.
 *
 * @param modelId - The model ID to check
 * @returns True if the model ID is a Gemini model
 */
function isGeminiModelId(
  modelId: string,
  options?: AllProviderOptions
): options is GoogleProviderOptions {
  return modelId.startsWith("gemini-") || modelId.startsWith("gemma-");
}

/**
 * Validates that an API key is available for the specified provider.
 *
 * @param provider - The provider name (for error messages)
 * @param apiKey - The API key from options or environment
 * @param envVar - The environment variable name (for error messages)
 * @throws {ModelConfigurationError} If API key is not configured
 */
function validateApiKey(
  provider: string,
  apiKey: string | undefined,
  envVar: string
): void {
  if (!apiKey) {
    throw new ModelConfigurationError(
      `API key for ${provider} is not configured. ` +
        `Please provide it via the 'apiKey' option or set the ${envVar} environment variable.`
    );
  }
}

/**
 * Detects the provider from the model ID and creates the appropriate provider instance.
 * Uses pattern matching to automatically determine which provider to use.
 *
 * @param modelId - Model ID (e.g., "gpt-5-nano", "claude-3-5-sonnet-latest", "gemini-2.0-flash")
 * @param options - Provider-specific configuration options
 * @returns Provider-specific model instance ready for use with Vercel AI SDK
 *
 * @throws {ModelConfigurationError} If the provider cannot be detected or API key is missing
 *
 * @example
 * ```typescript
 * // OpenAI with organization
 * const openaiModel = detectProvider("gpt-5-nano", {
 *   apiKey: "sk-...",
 *   organization: "org-123"
 * });
 *
 * // Anthropic
 * const anthropicModel = detectProvider("claude-3-5-sonnet-latest", {
 *   apiKey: "sk-ant-...",
 * });
 *
 * // Gemini
 * const geminiModel = detectProvider("gemini-2.0-flash", {
 *   apiKey: "...",
 * });
 * ```
 */
export function detectProvider(
  modelId: SupportedModelId,
  options?: Record<string, unknown>
): LanguageModel {
  if (isOpenAIModelId(modelId, options)) {
    const apiKey = (options?.apiKey as string) || process.env.OPENAI_API_KEY;
    validateApiKey("OpenAI", apiKey, "OPENAI_API_KEY");

    return createOpenAI({
      apiKey,
      ...options,
    })(modelId);
  }

  if (isAnthropicModelId(modelId, options)) {
    const apiKey = (options?.apiKey as string) || process.env.ANTHROPIC_API_KEY;
    validateApiKey("Anthropic", apiKey, "ANTHROPIC_API_KEY");

    return createAnthropic({
      apiKey,
      ...options,
    })(modelId);
  }

  if (isGeminiModelId(modelId, options)) {
    const apiKey = (options?.apiKey as string) || process.env.GOOGLE_API_KEY;
    validateApiKey("Google Gemini", apiKey, "GOOGLE_API_KEY");

    return createGoogleGenerativeAI({
      apiKey,
      ...options,
    })(modelId);
  }

  // If no provider matches, throw error
  throw new ModelConfigurationError(
    `Unable to detect provider for model ID: ${modelId}. ` +
      `Supported providers are OpenAI (gpt-*, o1*, o3*, chatgpt-*), ` +
      `Anthropic (claude-*), and Google Gemini (gemini-*, gemma-*).`
  );
}
