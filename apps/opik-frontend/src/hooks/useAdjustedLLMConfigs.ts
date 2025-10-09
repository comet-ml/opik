import { useMemo } from "react";
import { LLMPromptConfigsType } from "@/types/providers";

const MIN_RECOMMENDED_OUTPUT_TOKENS = 4000;

interface UseAdjustedLLMConfigsResult {
  adjustedConfigs: LLMPromptConfigsType;
  needsAdjustment: boolean;
  minTokens: number;
}

/**
 * Custom hook that validates and adjusts LLM configs to ensure sufficient token limits
 * for operations like prompt generation or improvement.
 *
 * @param configs - The original LLM prompt configurations
 * @param minTokens - Minimum recommended tokens (defaults to 2000)
 * @returns Object containing adjusted configs, whether adjustment was needed, and the min token value
 *
 * @example
 * const { adjustedConfigs, needsAdjustment } = useAdjustedLLMConfigs(configs);
 * if (needsAdjustment) {
 *   // Show info message to user
 * }
 * // Use adjustedConfigs for API call
 */
const useAdjustedLLMConfigs = (
  configs: LLMPromptConfigsType,
  minTokens: number = MIN_RECOMMENDED_OUTPUT_TOKENS,
): UseAdjustedLLMConfigsResult => {
  const result = useMemo(() => {
    // Handle empty configs
    if (!configs || Object.keys(configs).length === 0) {
      return {
        adjustedConfigs: configs,
        needsAdjustment: false,
        minTokens,
      };
    }

    let needsAdjustment = false;
    const adjustedConfigs = { ...configs };

    // Check for maxCompletionTokens (OpenAI, Anthropic, Gemini, VertexAI, Custom)
    if (
      "maxCompletionTokens" in adjustedConfigs &&
      typeof adjustedConfigs.maxCompletionTokens === "number"
    ) {
      if (adjustedConfigs.maxCompletionTokens < minTokens) {
        adjustedConfigs.maxCompletionTokens = minTokens;
        needsAdjustment = true;
      }
    }

    // Check for maxTokens (OpenRouter)
    if (
      "maxTokens" in adjustedConfigs &&
      typeof adjustedConfigs.maxTokens === "number"
    ) {
      if (adjustedConfigs.maxTokens < minTokens) {
        adjustedConfigs.maxTokens = minTokens;
        needsAdjustment = true;
      }
    }

    return {
      adjustedConfigs,
      needsAdjustment,
      minTokens,
    };
  }, [configs, minTokens]);

  return result;
};

export default useAdjustedLLMConfigs;
