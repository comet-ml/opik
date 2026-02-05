import { LLMProviderDetectionResult, LLMProvider } from "./types";
import { getProvider, getAllProviders } from "./providers/registry";

/**
 * Detects if the provided data supports LLM messages pretty mode rendering.
 *
 * Detection strategy:
 * 1. If provider hint is provided, try that provider first
 * 2. Fall back to trying all registered providers
 *
 * @param data - The raw trace/span input or output data
 * @param prettifyConfig - Configuration indicating if this is input or output
 * @param providerHint - Optional provider string hint from the span
 * @returns Detection result with supported flag and detected provider
 */
export const detectLLMMessages = (
  data: unknown,
  prettifyConfig?: { fieldType?: "input" | "output" },
  providerHint?: string,
): LLMProviderDetectionResult => {
  const isEmpty =
    data == null ||
    (typeof data === "object" && Object.keys(data as object).length === 0);

  if (isEmpty) {
    return { supported: false, empty: true };
  }

  // If provider hint provided, try that first
  if (providerHint) {
    const provider = getProvider(providerHint as LLMProvider);
    if (provider && provider.detector(data, prettifyConfig)) {
      return {
        supported: true,
        provider: provider.name,
        confidence: "high",
      };
    }
  }

  // Auto-detect by trying all providers
  const providers = getAllProviders();
  for (const provider of providers) {
    if (provider.detector(data, prettifyConfig)) {
      return {
        supported: true,
        provider: provider.name,
        confidence: providerHint ? "low" : "medium",
      };
    }
  }

  return { supported: false };
};

export default detectLLMMessages;
