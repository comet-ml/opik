import { PROVIDER_TYPE } from "@/types/providers";
import { Trace, Span } from "@/types/traces";
import { supportsPrettyView as schemaSupportsPrettyView } from "@/lib/provider-schemas";
import isString from "lodash/isString";
import isObject from "lodash/isObject";

/**
 * Detects the LLM provider from trace or span data
 */
export const detectProvider = (data: Trace | Span): PROVIDER_TYPE | null => {
  // 1. Check if provider is explicitly set in span (most reliable)
  if ("provider" in data && isString(data.provider)) {
    return data.provider as PROVIDER_TYPE;
  }

  // 2. Check metadata for provider information (very reliable)
  if (isObject(data.metadata) && "provider" in data.metadata) {
    const provider = data.metadata.provider;
    if (isString(provider)) {
      return provider as PROVIDER_TYPE;
    }
  }

  // 3. Check model name to infer provider (reliable for most cases)
  if ("model" in data && isString(data.model)) {
    return detectProviderFromModel(data.model);
  }

  // 4. Check metadata for model information
  if (isObject(data.metadata) && "model" in data.metadata) {
    const model = data.metadata.model;
    if (isString(model)) {
      return detectProviderFromModel(model);
    }
  }

  // 5. Check input/output structure for provider-specific patterns (least reliable)
  return detectProviderFromStructure(data);
};

/**
 * Detects provider from model name
 */
const detectProviderFromModel = (model: string): PROVIDER_TYPE | null => {
  const modelLower = model.toLowerCase();

  // OpenAI models
  if (modelLower.startsWith("gpt-") || modelLower.startsWith("o1-")) {
    return PROVIDER_TYPE.OPEN_AI;
  }

  // Anthropic models
  if (modelLower.startsWith("claude-")) {
    return PROVIDER_TYPE.ANTHROPIC;
  }

  // Google Gemini models (check before Vertex AI to avoid conflicts)
  if (
    modelLower.startsWith("gemini-") ||
    modelLower.startsWith("google/gemini")
  ) {
    return PROVIDER_TYPE.GEMINI;
  }

  // Vertex AI models (more specific detection)
  if (modelLower.startsWith("vertex_ai/") || modelLower.includes("vertex-ai")) {
    return PROVIDER_TYPE.VERTEX_AI;
  }

  // OpenRouter models (fallback for various providers)
  if (modelLower.includes("openrouter") || modelLower.includes("openai/")) {
    return PROVIDER_TYPE.OPEN_ROUTER;
  }

  return null;
};

/**
 * Detects provider from input/output structure patterns
 */
const detectProviderFromStructure = (
  data: Trace | Span,
): PROVIDER_TYPE | null => {
  // Check for OpenAI structure - look for the distinctive "choices" output
  if (isObject(data.output) && "choices" in data.output) {
    return PROVIDER_TYPE.OPEN_AI;
  }

  // Check for Anthropic structure - look for direct "content" in output
  if (
    isObject(data.output) &&
    "content" in data.output &&
    !("choices" in data.output)
  ) {
    return PROVIDER_TYPE.ANTHROPIC;
  }

  // Check for Google/Gemini structure - distinctive "contents" field
  if (isObject(data.input) && "contents" in data.input) {
    return PROVIDER_TYPE.GEMINI;
  }

  // Note: We can't distinguish OpenAI vs Anthropic based on input "messages" alone
  // since both use the same structure. We need to rely on output structure or
  // explicit provider information from metadata/model names.

  return null;
};

/**
 * Gets provider display name
 */
export const getProviderDisplayName = (provider: PROVIDER_TYPE): string => {
  const displayNames: Record<PROVIDER_TYPE, string> = {
    [PROVIDER_TYPE.OPEN_AI]: "OpenAI",
    [PROVIDER_TYPE.ANTHROPIC]: "Anthropic",
    [PROVIDER_TYPE.OPEN_ROUTER]: "OpenRouter",
    [PROVIDER_TYPE.GEMINI]: "Gemini",
    [PROVIDER_TYPE.VERTEX_AI]: "Vertex AI",
    [PROVIDER_TYPE.CUSTOM]: "Custom",
  };

  return displayNames[provider] || "Unknown";
};

/**
 * Checks if a provider supports pretty view formatting
 * Delegates to provider-schemas to ensure consistency
 */
export const supportsPrettyView = (provider: PROVIDER_TYPE): boolean => {
  return schemaSupportsPrettyView(provider);
};
