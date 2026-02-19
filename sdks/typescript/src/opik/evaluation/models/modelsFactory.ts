import type { LanguageModel } from "ai";
import { OpikBaseModel } from "./OpikBaseModel";
import {
  VercelAIChatModel,
  VercelAIChatModelOptions,
} from "./VercelAIChatModel";
import { type SupportedModelId } from "./providerDetection";

/**
 * Default model used when no model is specified in evaluation functions.
 */
const DEFAULT_MODEL: SupportedModelId = "gpt-5-nano";

/**
 * Factory function to create model instances with type-safe provider options.
 *
 * Supports multiple providers (OpenAI, Anthropic, Google Gemini) with automatic
 * provider detection based on model ID patterns.
 *
 * @param modelId - Model ID (e.g., 'gpt-5-nano', 'claude-3-5-sonnet-latest', 'gemini-2.0-flash')
 * @param options - Optional provider-specific configuration options
 * @returns An OpikBaseModel instance
 *
 * @example
 * ```typescript
 * // OpenAI with organization
 * const model1 = createModel('gpt-5-nano', {
 *   apiKey: 'sk-...',
 *   organization: 'org-123'
 * });
 *
 * // Anthropic
 * const model2 = createModel('claude-3-5-sonnet-latest', {
 *   apiKey: 'sk-ant-...',
 * });
 *
 * // Gemini
 * const model3 = createModel('gemini-2.0-flash', {
 *   apiKey: '...',
 * });
 * ```
 */
export function createModel(
  modelId: SupportedModelId,
  options?: VercelAIChatModelOptions
): OpikBaseModel {
  return new VercelAIChatModel(modelId, options);
}

/**
 * Wraps a pre-configured LanguageModel instance for use in Opik evaluations.
 *
 * Use this when you need maximum flexibility and want to configure the model
 * yourself using the Vercel AI SDK provider packages.
 *
 * @param languageModel - A pre-configured LanguageModel instance from Vercel AI SDK
 * @param options - Optional configuration options (trackGenerations defaults to true)
 * @returns An OpikBaseModel instance
 *
 * @example
 * ```typescript
 * import { openai } from '@ai-sdk/openai';
 * import { anthropic } from '@ai-sdk/anthropic';
 *
 * // OpenAI with custom settings
 * const customOpenAI = openai('gpt-5-nano', {
 *   structuredOutputs: true,
 * });
 * const model1 = createModelFromInstance(customOpenAI);
 *
 * // Anthropic with custom settings
 * const customAnthropic = anthropic('claude-3-5-sonnet-latest', {
 *   cacheControl: true,
 * });
 * const model2 = createModelFromInstance(customAnthropic);
 * ```
 */
export function createModelFromInstance(
  languageModel: LanguageModel,
  options?: VercelAIChatModelOptions
): OpikBaseModel {
  return new VercelAIChatModel(languageModel, options);
}

// ============================================================================
// Model Resolution
// ============================================================================

/**
 * Type guard to check if a value is an OpikBaseModel instance.
 */
function isOpikBaseModel(value: unknown): value is OpikBaseModel {
  return value instanceof OpikBaseModel;
}

/**
 * Type guard to check if a value is a LanguageModel instance.
 * LanguageModel instances from Vercel AI SDK have a modelId property.
 */
function isLanguageModel(value: unknown): value is LanguageModel {
  return (
    typeof value === "object" &&
    value !== null &&
    "modelId" in value &&
    typeof (value as Record<string, unknown>).modelId === "string"
  );
}

/**
 * Type guard to check if a value is a supported model ID string.
 */
function isSupportedModelId(value: unknown): value is SupportedModelId {
  return typeof value === "string" && value.length > 0;
}

/**
 * Creates a descriptive error for invalid model types.
 */
function createInvalidModelTypeError(model: unknown): Error {
  const receivedType = typeof model;
  const receivedValue =
    receivedType === "object" ? JSON.stringify(model) : String(model);

  return new Error(
    `Invalid model type. Expected one of:
  - string (model ID like 'gpt-5-nano', 'claude-3-5-sonnet-latest')
  - LanguageModel instance from Vercel AI SDK
  - OpikBaseModel instance
  - undefined (uses default model: ${DEFAULT_MODEL})

Received: ${receivedType} ${receivedValue}`
  );
}

/**
 * Resolves a model identifier to an OpikBaseModel instance.
 *
 * This function implements a resolution strategy that handles multiple input types:
 * 1. undefined/null → Creates default model (gpt-5-nano)
 * 2. string → Creates model from model ID
 * 3. OpikBaseModel → Returns as-is
 * 4. LanguageModel → Wraps in OpikBaseModel adapter
 *
 * @param model - Model identifier, instance, or undefined for default
 * @returns OpikBaseModel instance ready for evaluation
 * @throws {Error} When model type is invalid or unsupported
 *
 * @example
 * ```typescript
 * import { resolveModel } from 'opik/evaluation/models';
 * import { openai } from '@ai-sdk/openai';
 *
 * // Using default model
 * const model1 = resolveModel();
 *
 * // Using model ID
 * const model2 = resolveModel('gpt-5-nano');
 *
 * // Using OpikBaseModel instance
 * const model3 = resolveModel(new VercelAIChatModel('gpt-5-nano'));
 *
 * // Using LanguageModel instance
 * const model4 = resolveModel(openai('gpt-5-nano'));
 * ```
 */

export function resolveModel(
  model?: SupportedModelId | LanguageModel | OpikBaseModel,
  options?: VercelAIChatModelOptions
): OpikBaseModel {
  // Handle undefined/null → use default
  if (model === undefined || model === null) {
    return createModel(DEFAULT_MODEL, options);
  }

  // Handle string → create from model ID
  if (isSupportedModelId(model)) {
    return createModel(model, options);
  }

  // Handle OpikBaseModel → return as-is
  if (isOpikBaseModel(model)) {
    return model;
  }

  // Handle LanguageModel → wrap in adapter
  if (isLanguageModel(model)) {
    return createModelFromInstance(model, options);
  }

  // Invalid type → throw descriptive error
  throw createInvalidModelTypeError(model);
}
