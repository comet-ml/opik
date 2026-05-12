import {
  AnthropicThinkingEffort,
  COMPOSED_PROVIDER_TYPE,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
  ReasoningEffort,
} from "@/types/providers";
import {
  ANTHROPIC_MODEL_CAPABILITIES,
  DEFAULT_ANTHROPIC_CONFIGS,
  OPENAI_MODEL_CAPABILITIES,
  REASONING_MODELS,
} from "@/constants/llm";
import {
  getProviderFromModel,
  parseComposedProviderType,
} from "@/lib/provider";
import { getLatestModelFlags } from "@/lib/modelRegistryStore";

/**
 * Checks if a model is a reasoning model that requires temperature = 1.0.
 *
 * For OpenAI models, OPENAI_MODEL_CAPABILITIES is authoritative — every
 * gating decision (sampling sliders, effort dropdown, request stripping)
 * keys off the same map, so it must also answer the umbrella question.
 *
 * For other providers, the backend-fetched registry wins (via the module-
 * level flag index populated by useLLMProviderModelsData), with the
 * hardcoded REASONING_MODELS list as a pre-fetch fallback.
 */
export const isReasoningModel = (model?: PROVIDER_MODEL_TYPE | ""): boolean => {
  if (!model) return false;

  // OpenAI: capability map is the source of truth, mirroring how Anthropic
  // owns its supportsAnthropicThinkingEffort gating without consulting the
  // BE flag. Stops a BE YAML entry without `reasoning: true` from silently
  // disabling the playground reasoning-effort dropdown.
  if (
    getProviderFromModel(model as PROVIDER_MODEL_TYPE) === PROVIDER_TYPE.OPEN_AI
  ) {
    return OPENAI_MODEL_CAPABILITIES[model]?.reasoning ?? false;
  }

  // Other providers: BE flag wins; fall back to hardcoded REASONING_MODELS.
  const fetched = getLatestModelFlags(model);
  if (fetched !== undefined) {
    return fetched.reasoning;
  }
  return (REASONING_MODELS as readonly PROVIDER_MODEL_TYPE[]).includes(
    model as PROVIDER_MODEL_TYPE,
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
    model === PROVIDER_MODEL_TYPE.GEMINI_3_1_PRO ||
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
  return (
    model === PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_1_PRO ||
    model === PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_PRO
  );
};

const EFFORT_LABELS: Record<AnthropicThinkingEffort, string> = {
  adaptive: "Adaptive",
  low: "Low",
  medium: "Medium",
  high: "High (Default)",
  xhigh: "xHigh",
  max: "Max",
};

export const supportsSamplingParams = (
  model?: PROVIDER_MODEL_TYPE | "",
): boolean =>
  ANTHROPIC_MODEL_CAPABILITIES[model as PROVIDER_MODEL_TYPE]
    ?.supportsSamplingParams ?? true;

export const supportsAnthropicThinkingEffort = (
  model?: PROVIDER_MODEL_TYPE | "",
): boolean =>
  !!ANTHROPIC_MODEL_CAPABILITIES[model as PROVIDER_MODEL_TYPE]
    ?.thinkingEffortOptions;

export const getAnthropicThinkingEffortOptions = (
  model?: PROVIDER_MODEL_TYPE | "",
): Array<{ label: string; value: AnthropicThinkingEffort }> =>
  (
    ANTHROPIC_MODEL_CAPABILITIES[model as PROVIDER_MODEL_TYPE]
      ?.thinkingEffortOptions ?? []
  ).map((value) => ({ label: EFFORT_LABELS[value], value }));

const OPENAI_EFFORT_LABELS: Record<ReasoningEffort, string> = {
  none: "None",
  minimal: "Minimal",
  low: "Low",
  medium: "Medium",
  high: "High (Default)",
  xhigh: "xHigh",
};

export const supportsOpenAIReasoningEffort = (
  model?: PROVIDER_MODEL_TYPE | "",
): boolean =>
  !!OPENAI_MODEL_CAPABILITIES[model as PROVIDER_MODEL_TYPE]
    ?.reasoningEffortOptions;

export const getOpenAIReasoningEffortOptions = (
  model?: PROVIDER_MODEL_TYPE | "",
): Array<{ label: string; value: ReasoningEffort }> =>
  (
    OPENAI_MODEL_CAPABILITIES[model as PROVIDER_MODEL_TYPE]
      ?.reasoningEffortOptions ?? []
  ).map((value) => ({ label: OPENAI_EFFORT_LABELS[value], value }));

// Single reconciler called by every model-change handler (playground, judge
// dialog). Keeping the rules here means the form state stays valid even when
// the user switches models without opening the config dropdown.
export const updateProviderConfig = <
  T extends {
    temperature?: number;
    topP?: number;
    thinkingEffort?: AnthropicThinkingEffort;
    reasoningEffort?: ReasoningEffort;
  },
>(
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

  if (providerType === PROVIDER_TYPE.OPEN_AI) {
    const next: T = { ...currentConfig };
    let changed = false;

    // Reasoning models reject temperature < 1; coerce.
    if (
      isReasoningModel(params.model) &&
      typeof next.temperature === "number" &&
      next.temperature < 1
    ) {
      next.temperature = 1.0;
      changed = true;
    }

    // reasoningEffort: drop it for models without an effort option list,
    // coerce stale values to "high" otherwise. Mirrors the Anthropic
    // thinkingEffort handling below.
    const effortOptions = getOpenAIReasoningEffortOptions(params.model);
    if (effortOptions.length === 0) {
      if (next.reasoningEffort !== undefined) {
        next.reasoningEffort = undefined;
        changed = true;
      }
    } else if (
      next.reasoningEffort !== undefined &&
      !effortOptions.some((o) => o.value === next.reasoningEffort)
    ) {
      next.reasoningEffort = "high";
      changed = true;
    }

    return changed ? next : currentConfig;
  }

  if (providerType === PROVIDER_TYPE.ANTHROPIC) {
    const next: T = { ...currentConfig };
    let changed = false;

    if (!supportsSamplingParams(params.model)) {
      if (next.temperature !== undefined) {
        next.temperature = undefined;
        changed = true;
      }
      if (next.topP !== undefined) {
        next.topP = undefined;
        changed = true;
      }
    }

    const effortOptions = getAnthropicThinkingEffortOptions(params.model);
    if (effortOptions.length === 0) {
      if (next.thinkingEffort !== undefined) {
        next.thinkingEffort = undefined;
        changed = true;
      }
    } else if (
      next.thinkingEffort !== undefined &&
      !effortOptions.some((o) => o.value === next.thinkingEffort)
    ) {
      next.thinkingEffort = "high";
      changed = true;
    }

    return changed ? next : currentConfig;
  }

  return currentConfig;
};

// Last-mile request hardening, complementary to updateProviderConfig: this
// layer doesn't trust upstream and keeps the payload valid for stale state
// (e.g. older persisted prompts missing maxCompletionTokens).
export const sanitizeConfigForRequest = (
  model: PROVIDER_MODEL_TYPE | "",
  configs: Record<string, unknown>,
): Record<string, unknown> => {
  if (!model) return configs;

  const sanitized: Record<string, unknown> = { ...configs };
  const provider = getProviderFromModel(model as PROVIDER_MODEL_TYPE);

  if (provider === PROVIDER_TYPE.ANTHROPIC) {
    if (!supportsSamplingParams(model)) {
      delete sanitized.temperature;
      delete sanitized.topP;
    } else if (sanitized.topP != null && sanitized.temperature != null) {
      delete sanitized.topP;
    }
    if (sanitized.maxCompletionTokens == null) {
      sanitized.maxCompletionTokens =
        DEFAULT_ANTHROPIC_CONFIGS.MAX_COMPLETION_TOKENS;
    }
  }

  if (provider === PROVIDER_TYPE.OPEN_AI && sanitized.reasoningEffort != null) {
    if (!supportsOpenAIReasoningEffort(model)) {
      delete sanitized.reasoningEffort;
    } else {
      const allowed = getOpenAIReasoningEffortOptions(model).map(
        (o) => o.value,
      );
      if (!allowed.includes(sanitized.reasoningEffort as ReasoningEffort)) {
        delete sanitized.reasoningEffort;
      }
    }
  }

  return sanitized;
};
