import { PROVIDER_MODEL_TYPE, COMPOSED_PROVIDER_TYPE } from "@/types/providers";
import { REASONING_MODELS, ANTHROPIC_THINKING_MODELS } from "@/constants/llm";
import { PROVIDER_TYPE } from "@/types/providers";
import { parseComposedProviderType } from "@/lib/provider";

/**
 * Checks if a model is a reasoning model that requires temperature = 1.0
 * Reasoning models include GPT-5 family and O-series (O1, O3, O4-mini)
 *
 * @param model - The model type to check
 * @returns true if the model is a reasoning model, false otherwise
 */
export const isReasoningModel = (model?: PROVIDER_MODEL_TYPE | ""): boolean => {
  return Boolean(
    model &&
      (REASONING_MODELS as readonly PROVIDER_MODEL_TYPE[]).includes(
        model as PROVIDER_MODEL_TYPE,
      ),
  );
};

/**
 * Returns the default temperature for a given model
 * Reasoning models require temperature = 1.0, other models default to 0
 *
 * @param model - The model type
 * @returns 1.0 for reasoning models, 0 for all other models
 */
export const getDefaultTemperatureForModel = (
  model?: PROVIDER_MODEL_TYPE | "",
): number => {
  return isReasoningModel(model) ? 1 : 0;
};

/**
 * Checks if a Gemini model supports thinking level parameter
 * Currently Gemini 3 Pro and Gemini 3 Flash support thinking level
 *
 * @param model - The model type to check
 * @returns true if the model supports thinking level, false otherwise
 */
export const supportsGeminiThinkingLevel = (
  model?: PROVIDER_MODEL_TYPE | "",
): boolean => {
  return (
    model === PROVIDER_MODEL_TYPE.GEMINI_3_PRO ||
    model === PROVIDER_MODEL_TYPE.GEMINI_3_FLASH
  );
};

/**
 * Checks if a Vertex AI model supports thinking level parameter
 * Currently only Vertex AI Gemini 3 Pro supports thinking level
 *
 * @param model - The model type to check
 * @returns true if the model supports thinking level, false otherwise
 */
export const supportsVertexAIThinkingLevel = (
  model?: PROVIDER_MODEL_TYPE | "",
): boolean => {
  return model === PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_PRO;
};

/**
 * Checks if an Anthropic model supports thinking effort parameter
 * Currently only Claude Opus 4.6 supports adaptive thinking with effort levels
 *
 * @param model - The model type to check
 * @returns true if the model supports thinking effort, false otherwise
 */
export const supportsAnthropicThinkingEffort = (
  model?: PROVIDER_MODEL_TYPE | "",
): boolean => {
  return Boolean(
    model &&
      (ANTHROPIC_THINKING_MODELS as readonly PROVIDER_MODEL_TYPE[]).includes(
        model as PROVIDER_MODEL_TYPE,
      ),
  );
};

/**
 * Updates provider config to ensure reasoning models have temperature >= 1.0
 * This function ensures that OpenAI reasoning models (GPT-5 family, O-series)
 * have their temperature set to at least 1.0, as they don't support temperature < 1
 *
 * @param currentConfig - The current provider config (can be LLMPromptConfigsType or a more specific config type)
 * @param params - Configuration object containing model and provider
 * @param params.model - The model type
 * @param params.provider - The composed provider type
 * @returns Updated config with temperature adjusted if needed, or the original config
 */
export const updateProviderConfig = <T extends { temperature?: number }>(
  currentConfig: T | undefined,
  params: {
    model: PROVIDER_MODEL_TYPE | "";
    provider: COMPOSED_PROVIDER_TYPE;
  },
): T | undefined => {
  if (!currentConfig) {
    return currentConfig;
  }

  const providerType = parseComposedProviderType(params.provider);

  // Only adjust temperature for OpenAI reasoning models
  if (
    providerType === PROVIDER_TYPE.OPEN_AI &&
    isReasoningModel(params.model) &&
    typeof currentConfig.temperature === "number" &&
    currentConfig.temperature < 1
  ) {
    return {
      ...currentConfig,
      temperature: 1.0,
    };
  }

  return currentConfig;
};
