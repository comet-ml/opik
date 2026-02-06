import {
  COMPOSED_PROVIDER_TYPE,
  GeminiThinkingLevel,
  PROVIDER_MODEL_TYPE,
  ReasoningEffort,
} from "@/types/providers";
import {
  ANTHROPIC_ADAPTIVE_THINKING_MODELS,
  ANTHROPIC_EFFORT_BY_MODEL,
  ANTHROPIC_EXTENDED_THINKING_MODELS,
  DEFAULT_REASONING_EFFORTS,
  GEMINI_THINKING_LEVEL_BY_MODEL,
  OPENROUTER_REASONING_EFFORT_GEMINI3_PREFIXES,
  OPENROUTER_REASONING_EFFORT_MAXTOKENS_PREFIXES,
  OPENROUTER_REASONING_EFFORT_OPENAI_XAI_PREFIXES,
  OPENROUTER_REASONING_EFFORTS_GEMINI3,
  OPENROUTER_REASONING_EFFORTS_MAXTOKENS,
  OPENROUTER_REASONING_EFFORTS_OPENAI_XAI,
  OPENAI_REASONING_EFFORT_BY_MODEL,
  REASONING_EFFORT_ORDER,
  REASONING_MODELS,
  THINKING_LEVEL_ORDER,
} from "@/constants/llm";
import { PROVIDER_TYPE } from "@/types/providers";
import { parseComposedProviderType } from "@/lib/provider";

const normalizeModelIdentifier = (
  model?: PROVIDER_MODEL_TYPE | "",
): PROVIDER_MODEL_TYPE | "" | undefined => {
  if (!model) {
    return model;
  }

  const normalized = model.includes("/")
    ? model.substring(model.lastIndexOf("/") + 1)
    : model;

  return normalized as PROVIDER_MODEL_TYPE;
};

const isProviderModelMatch = (model: string, prefix: string): boolean => {
  return model.startsWith(prefix) || model.includes(`/${prefix}`);
};

const getSupportedOpenRouterReasoningEfforts = (
  model?: PROVIDER_MODEL_TYPE | "",
): readonly ReasoningEffort[] => {
  if (!model) {
    return [];
  }

  const rawModel = model.toLowerCase();

  if (
    OPENROUTER_REASONING_EFFORT_OPENAI_XAI_PREFIXES.some((prefix) =>
      isProviderModelMatch(rawModel, prefix),
    )
  ) {
    return OPENROUTER_REASONING_EFFORTS_OPENAI_XAI;
  }

  if (
    OPENROUTER_REASONING_EFFORT_GEMINI3_PREFIXES.some((prefix) =>
      isProviderModelMatch(rawModel, prefix),
    )
  ) {
    return OPENROUTER_REASONING_EFFORTS_GEMINI3;
  }

  if (
    OPENROUTER_REASONING_EFFORT_MAXTOKENS_PREFIXES.some((prefix) =>
      isProviderModelMatch(rawModel, prefix),
    ) ||
    rawModel.includes(":thinking") ||
    rawModel.includes("-thinking")
  ) {
    return OPENROUTER_REASONING_EFFORTS_MAXTOKENS;
  }

  return [];
};

/**
 * Checks if a model is a reasoning model that requires temperature = 1.0
 * Reasoning models include GPT-5 family and O-series (O1, O3, O4-mini)
 *
 * @param model - The model type to check
 * @returns true if the model is a reasoning model, false otherwise
 */
export const isReasoningModel = (model?: PROVIDER_MODEL_TYPE | ""): boolean => {
  const normalizedModel = normalizeModelIdentifier(model);

  return Boolean(
    (model || normalizedModel) &&
      ((model &&
        (REASONING_MODELS as readonly PROVIDER_MODEL_TYPE[]).includes(
          model as PROVIDER_MODEL_TYPE,
        )) ||
        (normalizedModel &&
          (REASONING_MODELS as readonly PROVIDER_MODEL_TYPE[]).includes(
            normalizedModel as PROVIDER_MODEL_TYPE,
          ))),
  );
};

const downgradeToSupportedLevel = <T extends string>(
  value: T | undefined,
  supportedLevels: readonly T[],
  orderedLevels: readonly T[],
): T | undefined => {
  if (!value || supportedLevels.includes(value)) {
    return value;
  }

  const startIndex = orderedLevels.indexOf(value);
  for (let i = startIndex - 1; i >= 0; i--) {
    if (supportedLevels.includes(orderedLevels[i])) {
      return orderedLevels[i];
    }
  }

  return supportedLevels[0];
};

export const getSupportedReasoningEfforts = (
  model?: PROVIDER_MODEL_TYPE | "",
): readonly ReasoningEffort[] => {
  const normalizedModel = normalizeModelIdentifier(model);
  const explicitModelSupport =
    (normalizedModel &&
      OPENAI_REASONING_EFFORT_BY_MODEL[
        normalizedModel as PROVIDER_MODEL_TYPE
      ]) ||
    (model && OPENAI_REASONING_EFFORT_BY_MODEL[model as PROVIDER_MODEL_TYPE]);

  if (explicitModelSupport) {
    return explicitModelSupport;
  }

  const openRouterSupport = getSupportedOpenRouterReasoningEfforts(model);
  if (openRouterSupport.length) {
    return openRouterSupport;
  }

  return isReasoningModel(model) ? DEFAULT_REASONING_EFFORTS : [];
};

export const normalizeReasoningEffortForModel = (
  value: ReasoningEffort | undefined,
  model?: PROVIDER_MODEL_TYPE | "",
): ReasoningEffort | undefined => {
  const supported = getSupportedReasoningEfforts(model);
  if (!supported.length) {
    return value;
  }

  return downgradeToSupportedLevel(value, supported, REASONING_EFFORT_ORDER);
};

export const getSupportedThinkingLevels = (
  model?: PROVIDER_MODEL_TYPE | "",
): readonly GeminiThinkingLevel[] => {
  const normalizedModel = normalizeModelIdentifier(model);

  return (
    (normalizedModel &&
      GEMINI_THINKING_LEVEL_BY_MODEL[normalizedModel as PROVIDER_MODEL_TYPE]) ||
    (model && GEMINI_THINKING_LEVEL_BY_MODEL[model as PROVIDER_MODEL_TYPE]) ||
    []
  );
};

export const supportsAnthropicExtendedThinking = (
  model?: PROVIDER_MODEL_TYPE | "",
): boolean => {
  const normalizedModel = normalizeModelIdentifier(model);
  return Boolean(
    normalizedModel &&
      (ANTHROPIC_EXTENDED_THINKING_MODELS as readonly string[]).includes(
        normalizedModel,
      ),
  );
};

export const supportsAnthropicAdaptiveThinking = (
  model?: PROVIDER_MODEL_TYPE | "",
): boolean => {
  const normalizedModel = normalizeModelIdentifier(model);
  return Boolean(
    normalizedModel &&
      (ANTHROPIC_ADAPTIVE_THINKING_MODELS as readonly string[]).includes(
        normalizedModel,
      ),
  );
};

export const getSupportedAnthropicEfforts = (
  model?: PROVIDER_MODEL_TYPE | "",
): readonly ("low" | "medium" | "high" | "max")[] => {
  const normalizedModel = normalizeModelIdentifier(model);

  if (!normalizedModel) {
    return [];
  }

  return ANTHROPIC_EFFORT_BY_MODEL[normalizedModel] || [];
};

export const normalizeAnthropicEffortForModel = (
  value: "low" | "medium" | "high" | "max" | undefined,
  model?: PROVIDER_MODEL_TYPE | "",
): "low" | "medium" | "high" | "max" | undefined => {
  const supported = getSupportedAnthropicEfforts(model);
  if (!supported.length) {
    return value;
  }

  return downgradeToSupportedLevel(value, supported, [
    "low",
    "medium",
    "high",
    "max",
  ]);
};

export const normalizeThinkingLevelForModel = (
  value: GeminiThinkingLevel | undefined,
  model?: PROVIDER_MODEL_TYPE | "",
): GeminiThinkingLevel | undefined => {
  const supported = getSupportedThinkingLevels(model);
  if (!supported.length) {
    return undefined;
  }

  return downgradeToSupportedLevel(value, supported, THINKING_LEVEL_ORDER);
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
 * Updates provider config to ensure reasoning models have temperature >= 1.0
 * This function ensures that reasoning models using OpenAI-compatible routes
 * (OpenAI, OpenRouter, Custom/Azure) have their temperature set to at least 1.0.
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
  let updatedConfig = currentConfig;

  // Only adjust temperature for OpenAI-style reasoning models
  if (
    (providerType === PROVIDER_TYPE.OPEN_AI ||
      providerType === PROVIDER_TYPE.OPEN_ROUTER ||
      providerType === PROVIDER_TYPE.CUSTOM) &&
    isReasoningModel(params.model) &&
    typeof currentConfig.temperature === "number" &&
    currentConfig.temperature < 1
  ) {
    updatedConfig = {
      ...updatedConfig,
      temperature: 1.0,
    };
  }

  if (
    "reasoningEffort" in updatedConfig &&
    typeof updatedConfig.reasoningEffort === "string"
  ) {
    const normalizedReasoningEffort = normalizeReasoningEffortForModel(
      updatedConfig.reasoningEffort as ReasoningEffort,
      params.model,
    );

    if (
      normalizedReasoningEffort &&
      normalizedReasoningEffort !== updatedConfig.reasoningEffort
    ) {
      const nextConfig: T & {
        custom_parameters?: Record<string, unknown> | null;
      } = {
        ...updatedConfig,
        reasoningEffort: normalizedReasoningEffort,
      };

      if ("custom_parameters" in nextConfig) {
        nextConfig.custom_parameters = {
          ...((nextConfig.custom_parameters as Record<string, unknown>) || {}),
          reasoning: {
            effort: normalizedReasoningEffort,
          },
        };
      }

      updatedConfig = nextConfig;
    }
  }

  if ("thinkingLevel" in updatedConfig) {
    const normalizedThinkingLevel = normalizeThinkingLevelForModel(
      updatedConfig.thinkingLevel as GeminiThinkingLevel | undefined,
      params.model,
    );

    if (normalizedThinkingLevel !== updatedConfig.thinkingLevel) {
      updatedConfig = {
        ...updatedConfig,
        thinkingLevel: normalizedThinkingLevel,
      };
    }
  }

  if ("thinkingEffort" in updatedConfig) {
    const normalizedThinkingEffort = normalizeAnthropicEffortForModel(
      updatedConfig.thinkingEffort as
        | "low"
        | "medium"
        | "high"
        | "max"
        | undefined,
      params.model,
    );

    if (
      normalizedThinkingEffort &&
      normalizedThinkingEffort !== updatedConfig.thinkingEffort
    ) {
      const nextConfig: T & {
        custom_parameters?: Record<string, unknown> | null;
      } = {
        ...updatedConfig,
        thinkingEffort: normalizedThinkingEffort,
      };

      if ("custom_parameters" in nextConfig) {
        nextConfig.custom_parameters = {
          ...((nextConfig.custom_parameters as Record<string, unknown>) || {}),
          output_config: {
            effort: normalizedThinkingEffort,
          },
        };
      }

      updatedConfig = nextConfig;
    }
  }

  return updatedConfig;
};
