import {
  AnthropicThinkingEffort,
  COMPOSED_PROVIDER_TYPE,
  GeminiThinkingLevel,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
  ReasoningEffort,
} from "@/types/providers";
import {
  ANTHROPIC_MODEL_CAPABILITIES,
  DEFAULT_ANTHROPIC_CONFIGS,
  OPENAI_MODEL_CAPABILITIES,
  REASONING_MODELS,
  THINKING_LEVEL_OPTIONS_2_5_FLASH,
  THINKING_LEVEL_OPTIONS_2_5_PRO,
  THINKING_LEVEL_OPTIONS_FLASH,
  THINKING_LEVEL_OPTIONS_PRO,
} from "@/constants/llm";
import {
  getProviderFromModel,
  parseComposedProviderType,
} from "@/lib/provider";
import { getLatestModelFlags } from "@/lib/modelRegistryStore";

export const getRoutableProviderModelValue = (
  composedProviderType: COMPOSED_PROVIDER_TYPE,
  modelValue: string,
): PROVIDER_MODEL_TYPE => {
  const providerType = parseComposedProviderType(composedProviderType);

  if (providerType === PROVIDER_TYPE.VERTEX_AI && !modelValue.includes("/")) {
    return `vertex_ai/${modelValue}` as PROVIDER_MODEL_TYPE;
  }

  return modelValue as PROVIDER_MODEL_TYPE;
};

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

// Gemini 2.5 models take thinking through the same level control as Gemini 3. Flash and Flash Lite
// can disable it — 2.5 Flash Lite ships that way, so the customer-visible case is turning it on and
// turning it back off has to stay reachable — but 2.5 Pro cannot, so the two get different options.
const GEMINI_2_5_FLASH_THINKING_MODELS: readonly PROVIDER_MODEL_TYPE[] = [
  PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH,
  PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH_LITE,
  PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_FLASH,
  PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_FLASH_LITE_PREVIEW_06_17,
];

const GEMINI_2_5_PRO_THINKING_MODELS: readonly PROVIDER_MODEL_TYPE[] = [
  PROVIDER_MODEL_TYPE.GEMINI_2_5_PRO,
  PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_PRO,
];

const GEMINI_2_5_THINKING_MODELS: readonly PROVIDER_MODEL_TYPE[] = [
  PROVIDER_MODEL_TYPE.GEMINI_2_5_PRO,
  PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH,
  PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH_LITE,
];

const VERTEX_AI_2_5_THINKING_MODELS: readonly PROVIDER_MODEL_TYPE[] = [
  PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_PRO,
  PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_FLASH,
  PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_FLASH_LITE_PREVIEW_06_17,
];

/**
 * Checks if a Gemini model supports thinking level parameter
 * Gemini 3 Pro/Flash plus the Gemini 2.5 family
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
    model === PROVIDER_MODEL_TYPE.GEMINI_3_FLASH ||
    GEMINI_2_5_THINKING_MODELS.includes(model as PROVIDER_MODEL_TYPE)
  );
};

/**
 * Checks if a Vertex AI model supports thinking level parameter
 * Vertex AI Gemini 3 Pro plus the Vertex Gemini 2.5 family
 *
 * @param model - The model type to check
 * @returns true if the model supports thinking level, false otherwise
 */
export const supportsVertexAIThinkingLevel = (
  model?: PROVIDER_MODEL_TYPE | "",
): boolean => {
  return (
    model === PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_1_PRO ||
    model === PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_PRO ||
    VERTEX_AI_2_5_THINKING_MODELS.includes(model as PROVIDER_MODEL_TYPE)
  );
};

/**
 * The thinking levels a model accepts. Gemini families differ — Pro has no minimal, Flash has all
 * four, and 2.5 Flash adds "off" — and sending a level a model does not accept is rejected.
 */
export const getThinkingLevelOptions = (
  model?: PROVIDER_MODEL_TYPE | "",
): Array<{ label: string; value: GeminiThinkingLevel }> => {
  if (GEMINI_2_5_PRO_THINKING_MODELS.includes(model as PROVIDER_MODEL_TYPE)) {
    return THINKING_LEVEL_OPTIONS_2_5_PRO;
  }

  if (GEMINI_2_5_FLASH_THINKING_MODELS.includes(model as PROVIDER_MODEL_TYPE)) {
    return THINKING_LEVEL_OPTIONS_2_5_FLASH;
  }

  if (model === PROVIDER_MODEL_TYPE.GEMINI_3_FLASH) {
    return THINKING_LEVEL_OPTIONS_FLASH;
  }

  if (
    supportsGeminiThinkingLevel(model) ||
    supportsVertexAIThinkingLevel(model)
  ) {
    return THINKING_LEVEL_OPTIONS_PRO;
  }

  return [];
};

/**
 * The level to preselect. Flash Lite is the exception: Google ships it with thinking disabled, so
 * defaulting it to anything else would silently turn thinking on for a model that had it off.
 */
export const getDefaultThinkingLevel = (
  model?: PROVIDER_MODEL_TYPE | "",
): GeminiThinkingLevel => {
  if (
    model === PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH_LITE ||
    model === PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_FLASH_LITE_PREVIEW_06_17
  ) {
    return "off";
  }

  return "high";
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
  max: "Max",
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
    thinkingLevel?: GeminiThinkingLevel;
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

    // Reasoning models reject top_p outright (OpenAI returns 400 "Unsupported parameter:
    // 'top_p' is not supported with this model."). Drop any stale value so the next request
    // omits the field entirely. The Top P slider is hidden for these models in the UI.
    if (isReasoningModel(params.model) && next.topP !== undefined) {
      next.topP = undefined;
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

  if (
    providerType === PROVIDER_TYPE.GEMINI ||
    providerType === PROVIDER_TYPE.VERTEX_AI
  ) {
    const next: T = { ...currentConfig };
    let changed = false;

    // thinkingLevel: drop it for models without a level option list, coerce stale values to the
    // model's own default otherwise. Without this a level carried over from another model (an "off"
    // selected on 2.5 Flash Lite, say) leaves the dropdown showing a value it does not offer while
    // sanitizeConfigForRequest silently drops it from the request. Mirrors the handling above.
    const levelOptions = getThinkingLevelOptions(params.model);
    if (levelOptions.length === 0) {
      if (next.thinkingLevel !== undefined) {
        next.thinkingLevel = undefined;
        changed = true;
      }
    } else if (
      next.thinkingLevel !== undefined &&
      !levelOptions.some((o) => o.value === next.thinkingLevel)
    ) {
      next.thinkingLevel = getDefaultThinkingLevel(params.model);
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
  // Opt-in because only the playground proxy reads custom_parameters; the optimizer gateway takes
  // this same output as its flat llm_model.parameters and would see an unknown nested key.
  { foldThinkingLevel = false }: { foldThinkingLevel?: boolean } = {},
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

  // Strip top_p for OpenAI reasoning models — OpenAI rejects it with 400 "Unsupported
  // parameter: 'top_p' is not supported with this model." Belt-and-braces with the slider
  // gating and updateProviderConfig: stale persisted prompts that bypass the reconciler
  // still produce a valid wire payload.
  if (
    provider === PROVIDER_TYPE.OPEN_AI &&
    isReasoningModel(model) &&
    sanitized.topP != null
  ) {
    delete sanitized.topP;
  }

  // The playground body is a flat spread of the config, and the backend deserializes it into
  // langchain4j's ChatCompletionRequest, which ignores unknown top-level fields. A flat
  // thinking_level is therefore silently dropped, so it has to be nested under
  // custom_parameters — the only free-form slot the request actually captures.
  if (sanitized.thinkingLevel != null) {
    const level = sanitized.thinkingLevel as GeminiThinkingLevel;

    // Dropped unconditionally: the field is Opik's own, and no provider accepts it at the top
    // level, so leaving it on the payload can only be dead weight.
    delete sanitized.thinkingLevel;

    // Only fold it in when the model actually accepts that level — a stale selection left over
    // from another model (say "off" carried onto Gemini 3) would otherwise be rejected upstream.
    if (
      foldThinkingLevel &&
      getThinkingLevelOptions(model).some((o) => o.value === level)
    ) {
      const customParameters =
        (sanitized.custom_parameters as Record<string, unknown>) ?? {};
      // Merge into any existing thinking block rather than replacing it — the backend also reads
      // budget_tokens and include_thoughts from there, and only `level` is ours to set here.
      const thinking =
        (customParameters.thinking as Record<string, unknown>) ?? {};

      sanitized.custom_parameters = {
        ...customParameters,
        thinking: { ...thinking, level },
      };
    }
  }

  return sanitized;
};
