import { PROVIDER_MODEL_TYPE } from "@/types/providers";
import { REASONING_MODELS } from "@/constants/llm";

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
