import { LLMProvider, LLMProviderImplementation } from "../types";
import { openaiProvider } from "./openai";

// Registry of all providers
const PROVIDER_REGISTRY: Record<LLMProvider, LLMProviderImplementation | null> =
  {
    openai: openaiProvider,
    // Future providers - placeholders for now
    anthropic: null,
    google: null,
  };

/**
 * Get a provider implementation by name
 */
export const getProvider = (
  provider: LLMProvider,
): LLMProviderImplementation | null => {
  return PROVIDER_REGISTRY[provider] || null;
};

/**
 * Get all registered provider implementations
 */
export const getAllProviders = (): LLMProviderImplementation[] => {
  return Object.values(PROVIDER_REGISTRY).filter(
    (p): p is LLMProviderImplementation => p !== null,
  );
};
