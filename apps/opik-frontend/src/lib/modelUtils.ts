import {
  PROVIDER_MODEL_TYPE,
  LLMPromptConfigsType,
  COMPOSED_PROVIDER_TYPE,
  LLMOpenAIConfigsType,
} from "@/types/providers";
import { REASONING_MODELS } from "@/constants/llm";
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
 * Updates provider config to ensure reasoning models have temperature >= 1.0
 * This function ensures that OpenAI reasoning models (GPT-5 family, O-series)
 * have their temperature set to at least 1.0, as they don't support temperature < 1
 *
 * @param currentConfig - The current provider config
 * @param model - The model type
 * @param provider - The composed provider type
 * @returns Updated config with temperature adjusted if needed, or the original config
 */
export const updateProviderConfig = (
  currentConfig: LLMPromptConfigsType | undefined,
  model: PROVIDER_MODEL_TYPE | "",
  provider: COMPOSED_PROVIDER_TYPE,
): LLMPromptConfigsType | undefined => {
  if (!currentConfig) {
    return currentConfig;
  }

  const providerType = parseComposedProviderType(provider);

  // Only adjust temperature for OpenAI reasoning models
  if (
    providerType === PROVIDER_TYPE.OPEN_AI &&
    isReasoningModel(model) &&
    typeof (currentConfig as LLMOpenAIConfigsType).temperature === "number" &&
    (currentConfig as LLMOpenAIConfigsType).temperature < 1
  ) {
    return {
      ...currentConfig,
      temperature: 1.0,
    } as LLMPromptConfigsType;
  }

  return currentConfig;
};
