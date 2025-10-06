import { PROVIDER_TYPE } from "@/types/providers";
import { Trace, Span } from "@/types/traces";
import isString from "lodash/isString";
import isObject from "lodash/isObject";

/**
 * Detects the LLM provider from trace or span data
 */
export const detectProvider = (data: Trace | Span): PROVIDER_TYPE | null => {
  // Check if provider is explicitly set in span
  if ("provider" in data && isString(data.provider)) {
    return data.provider as PROVIDER_TYPE;
  }

  // Check metadata for provider information
  if (isObject(data.metadata) && "provider" in data.metadata) {
    const provider = data.metadata.provider;
    if (isString(provider)) {
      return provider as PROVIDER_TYPE;
    }
  }

  // Check model name to infer provider
  if ("model" in data && isString(data.model)) {
    return detectProviderFromModel(data.model);
  }

  // Check metadata for model information
  if (isObject(data.metadata) && "model" in data.metadata) {
    const model = data.metadata.model;
    if (isString(model)) {
      return detectProviderFromModel(model);
    }
  }

  // Check input/output structure for provider-specific patterns
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

  // Google models
  if (modelLower.startsWith("gemini-")) {
    return PROVIDER_TYPE.GEMINI;
  }

  // Vertex AI models
  if (modelLower.includes("vertex") || modelLower.includes("google")) {
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
const detectProviderFromStructure = (data: Trace | Span): PROVIDER_TYPE | null => {
  // Check for OpenAI structure
  if (isObject(data.input) && "messages" in data.input) {
    return PROVIDER_TYPE.OPEN_AI;
  }

  if (isObject(data.output) && "choices" in data.output) {
    return PROVIDER_TYPE.OPEN_AI;
  }

  // Check for Anthropic structure
  if (isObject(data.input) && "messages" in data.input) {
    const messages = data.input.messages;
    if (Array.isArray(messages) && messages.length > 0) {
      const firstMessage = messages[0];
      if (isObject(firstMessage) && "role" in firstMessage) {
        // Anthropic uses different role names
        if (firstMessage.role === "user" || firstMessage.role === "assistant") {
          return PROVIDER_TYPE.ANTHROPIC;
        }
      }
    }
  }

  // Check for Google/Gemini structure
  if (isObject(data.input) && "contents" in data.input) {
    return PROVIDER_TYPE.GEMINI;
  }

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
 */
export const supportsPrettyView = (provider: PROVIDER_TYPE): boolean => {
  const supportedProviders = [
    PROVIDER_TYPE.OPEN_AI,
    PROVIDER_TYPE.ANTHROPIC,
    PROVIDER_TYPE.GEMINI,
    PROVIDER_TYPE.VERTEX_AI,
  ];

  return supportedProviders.includes(provider);
};
