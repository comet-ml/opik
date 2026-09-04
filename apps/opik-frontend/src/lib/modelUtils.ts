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
} from "@/constants/llm";
import {
  getProviderFromModel,
  parseComposedProviderType,
} from "@/lib/provider";
import omit from "lodash/omit";
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

// Which thinking levels each Gemini model accepts, per Google's own support table
// (https://ai.google.dev/gemini-api/docs/thinking). The sets genuinely differ per model — 3.7 Flash
// has no "minimal", 3.1 Flash Lite has only "minimal" and "high" — and sending a level a model does
// not accept is rejected upstream, so this cannot be collapsed into one list per family.
//
// Keep both provider spellings of a model on the same row: the level support is a property of the
// underlying model, not of whether it is reached through AI Studio or Vertex. New models arrive via
// the automated `sync provider model definitions` PRs, which cannot know about this table — so a
// newly synced thinking model shows no control until it is added here.
const MINIMAL_TO_HIGH: readonly GeminiThinkingLevel[] = [
  "minimal",
  "low",
  "medium",
  "high",
];
const LOW_TO_HIGH: readonly GeminiThinkingLevel[] = ["low", "medium", "high"];

const THINKING_LEVELS_BY_MODEL: ReadonlyMap<
  PROVIDER_MODEL_TYPE,
  readonly GeminiThinkingLevel[]
> = new Map([
  // Gemini 3.x
  [PROVIDER_MODEL_TYPE.GEMINI_3_7_FLASH, LOW_TO_HIGH],
  [PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_7_FLASH, LOW_TO_HIGH],
  [PROVIDER_MODEL_TYPE.GEMINI_3_6_FLASH, MINIMAL_TO_HIGH],
  [PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_6_FLASH, MINIMAL_TO_HIGH],
  [PROVIDER_MODEL_TYPE.GEMINI_3_5_FLASH, MINIMAL_TO_HIGH],
  [PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_5_FLASH, MINIMAL_TO_HIGH],
  [PROVIDER_MODEL_TYPE.GEMINI_3_5_FLASH_LITE, ["none", ...MINIMAL_TO_HIGH]],
  [
    PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_5_FLASH_LITE,
    ["none", ...MINIMAL_TO_HIGH],
  ],
  [PROVIDER_MODEL_TYPE.GEMINI_3_1_PRO, LOW_TO_HIGH],
  [PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_1_PRO, LOW_TO_HIGH],
  // The Flash Lite models do not think by default — verified live: zero thinking tokens on both a
  // trivial and a deliberately hard prompt, on both providers. So they lead with "none", which sends
  // no thinkingConfig and keeps their latency where it was. Asking for a level here switches thinking
  // ON, which measurably slows them (~2.5s -> ~5s at budget 2048 on 3.1 Flash Lite).
  //
  // 3.1 Flash Lite also has no low/medium: minimal and high only.
  [PROVIDER_MODEL_TYPE.GEMINI_3_1_FLASH_LITE, ["none", "minimal", "high"]],
  [
    PROVIDER_MODEL_TYPE.GEMINI_3_1_FLASH_LITE_PREVIEW,
    ["none", "minimal", "high"],
  ],
  [
    PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_1_FLASH_LITE,
    ["none", "minimal", "high"],
  ],
  [
    PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_1_FLASH_LITE_PREVIEW,
    ["none", "minimal", "high"],
  ],
  [PROVIDER_MODEL_TYPE.GEMINI_3_FLASH, MINIMAL_TO_HIGH],
  [PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_FLASH_PREVIEW, MINIMAL_TO_HIGH],
  [PROVIDER_MODEL_TYPE.GEMINI_3_PRO, ["low", "high"]],
  [PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_PRO, ["low", "high"]],
  // Gemini 2.5 takes a numeric thinking_budget rather than a level, so these levels are translated
  // server-side. They lead with "auto" because a budget left unset is how Google's own default works
  // — "the model automatically controls how much it thinks up to a maximum of 8,192 tokens" — and
  // without it, merely opening the control would pin a hard budget over that default.
  //
  // Only Flash Lite gets "off": it is the one 2.5 model Google ships with thinking already off, and
  // 2.5 Pro cannot disable thinking at all.
  [PROVIDER_MODEL_TYPE.GEMINI_2_5_PRO, ["auto", ...LOW_TO_HIGH]],
  [PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_PRO, ["auto", ...LOW_TO_HIGH]],
  [PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH, ["auto", ...LOW_TO_HIGH]],
  [PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_FLASH, ["auto", ...LOW_TO_HIGH]],
  [PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH_LITE, ["auto", "off", ...LOW_TO_HIGH]],
  [
    PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_FLASH_LITE_PREVIEW_06_17,
    ["auto", "off", ...LOW_TO_HIGH],
  ],
]);

const THINKING_LEVEL_LABELS: Record<GeminiThinkingLevel, string> = {
  auto: "Auto",
  none: "None",
  off: "Off",
  minimal: "Minimal",
  low: "Low",
  medium: "Medium",
  high: "High",
};

// Which of the two providers a model id belongs to is encoded in the id itself (Vertex ids are
// namespaced `vertex_ai/...`). Deliberately not getProviderFromModel: that resolves through the
// runtime model registry, so it depends on fetched state and returns a fallback provider before
// the registry loads — a gate on it would flicker with load order.
const isVertexModel = (model?: PROVIDER_MODEL_TYPE | ""): boolean =>
  typeof model === "string" && model.startsWith("vertex_ai/");

/**
 * Checks if a Gemini model supports thinking level parameter
 *
 * @param model - The model type to check
 * @returns true if the model supports thinking level, false otherwise
 */
export const supportsGeminiThinkingLevel = (
  model?: PROVIDER_MODEL_TYPE | "",
): boolean =>
  !isVertexModel(model) &&
  THINKING_LEVELS_BY_MODEL.has(model as PROVIDER_MODEL_TYPE);

/**
 * Checks if a Vertex AI model supports thinking level parameter
 *
 * @param model - The model type to check
 * @returns true if the model supports thinking level, false otherwise
 */
export const supportsVertexAIThinkingLevel = (
  model?: PROVIDER_MODEL_TYPE | "",
): boolean =>
  isVertexModel(model) &&
  THINKING_LEVELS_BY_MODEL.has(model as PROVIDER_MODEL_TYPE);

/**
 * The thinking levels a model accepts, as select options. Empty for models without thinking.
 */
export const getThinkingLevelOptions = (
  model?: PROVIDER_MODEL_TYPE | "",
): Array<{ label: string; value: GeminiThinkingLevel }> =>
  (THINKING_LEVELS_BY_MODEL.get(model as PROVIDER_MODEL_TYPE) ?? []).map(
    (value) => ({ label: THINKING_LEVEL_LABELS[value], value }),
  );

// Each model's own default thinking level. Measured against the live API rather than taken from
// Google's docs table, which disagrees with it: the docs list 3.5 Flash Lite as defaulting to
// "minimal", but every Flash Lite model returns zero thinking tokens by default on both providers.
// Preselecting the
// documented default keeps the control from silently changing a model's behaviour just by being
// shown: 2.5 Flash Lite ships with thinking off, 2.5 Pro/Flash default to a dynamic budget
// ("auto"), 3.7/3.6/3.5 Flash default to medium, and 3.5 Flash Lite to minimal — none of which is
// "high". Models absent here default to "high", which is what the Gemini 3 Pro rows document.
const DEFAULT_THINKING_LEVEL_BY_MODEL: ReadonlyMap<
  PROVIDER_MODEL_TYPE,
  GeminiThinkingLevel
> = new Map([
  // Flash Lite ships with thinking off, so "off" rather than "auto" is its documented default.
  [PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH_LITE, "off" as GeminiThinkingLevel],
  [
    PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_FLASH_LITE_PREVIEW_06_17,
    "off" as GeminiThinkingLevel,
  ],
  [PROVIDER_MODEL_TYPE.GEMINI_2_5_PRO, "auto" as GeminiThinkingLevel],
  [PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_PRO, "auto" as GeminiThinkingLevel],
  [PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH, "auto" as GeminiThinkingLevel],
  [
    PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_FLASH,
    "auto" as GeminiThinkingLevel,
  ],
  [PROVIDER_MODEL_TYPE.GEMINI_3_7_FLASH, "medium" as GeminiThinkingLevel],
  [
    PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_7_FLASH,
    "medium" as GeminiThinkingLevel,
  ],
  [PROVIDER_MODEL_TYPE.GEMINI_3_6_FLASH, "medium" as GeminiThinkingLevel],
  [
    PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_6_FLASH,
    "medium" as GeminiThinkingLevel,
  ],
  [PROVIDER_MODEL_TYPE.GEMINI_3_5_FLASH, "medium" as GeminiThinkingLevel],
  [
    PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_5_FLASH,
    "medium" as GeminiThinkingLevel,
  ],
  [PROVIDER_MODEL_TYPE.GEMINI_3_5_FLASH_LITE, "none" as GeminiThinkingLevel],
  [
    PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_5_FLASH_LITE,
    "none" as GeminiThinkingLevel,
  ],
  [PROVIDER_MODEL_TYPE.GEMINI_3_1_FLASH_LITE, "none" as GeminiThinkingLevel],
  [
    PROVIDER_MODEL_TYPE.GEMINI_3_1_FLASH_LITE_PREVIEW,
    "none" as GeminiThinkingLevel,
  ],
  [
    PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_1_FLASH_LITE,
    "none" as GeminiThinkingLevel,
  ],
  [
    PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_1_FLASH_LITE_PREVIEW,
    "none" as GeminiThinkingLevel,
  ],
]);

/**
 * The level to preselect: the model's own documented default, so showing the control does not
 * change how the model behaves.
 */
export const getDefaultThinkingLevel = (
  model?: PROVIDER_MODEL_TYPE | "",
): GeminiThinkingLevel =>
  DEFAULT_THINKING_LEVEL_BY_MODEL.get(model as PROVIDER_MODEL_TYPE) ?? "high";

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

    // thinkingLevel: drop it for models without a level option list, otherwise make sure it holds a
    // level this model actually offers — coercing a stale one (an "off" carried over from 2.5 Flash
    // Lite) and filling in the default when unset. Setting it rather than only coercing is what keeps
    // the control honest: the dropdown falls back to the default for display, so leaving the config
    // empty would show a level that never gets sent. Mirrors the handling above.
    const levelOptions = getThinkingLevelOptions(params.model);
    if (levelOptions.length === 0) {
      if (next.thinkingLevel !== undefined) {
        next.thinkingLevel = undefined;
        changed = true;
      }
    } else if (!levelOptions.some((o) => o.value === next.thinkingLevel)) {
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

  // The request body is a flat spread of the config, and the backend deserializes it into
  // langchain4j's ChatCompletionRequest, which ignores unknown top-level fields. A flat
  // thinking_level is therefore silently dropped, so it has to be nested under
  // custom_parameters — the only free-form slot the request actually captures. This holds for
  // every caller: the playground proxy, experiment runs, and the optimizer, which all render the
  // same Gemini config panel and reach the model through the same request shape.
  const thinkingLevelOptions = getThinkingLevelOptions(model);

  if (sanitized.thinkingLevel != null || thinkingLevelOptions.length > 0) {
    // Fall back to the model's default when the config holds no level. The control displays that
    // same default, so without this a prompt persisted before the level existed shows one value and
    // sends none — the reconciler only fills the config in on a model change, and a stored prompt
    // whose model is still valid is never reconciled at all.
    // Both stale cases resolve the same way, to the model's default: a config with no level (persisted
    // before the control existed) and a config holding a level this model does not offer (carried over
    // from another model outside the reconciler). Either way the dropdown displays the default, so the
    // request has to send it rather than nothing.
    // A level already nested under custom_parameters counts as stored. Callers that persist the
    // sanitized output and feed it back — the optimizer form reloads a saved run's `parameters`
    // blob wholesale — have no flat thinkingLevel to offer, and substituting the model default
    // there would silently reset the user's saved choice on every re-run.
    // A nested level the model still offers is a real past choice and is honoured — including on the
    // Flash Lite models, where an explicitly saved "minimal" keeps thinking on. Only the *default*
    // changed to "none"; a level someone chose is not overridden.
    const nested = (
      (sanitized.custom_parameters as Record<string, unknown> | undefined)
        ?.thinking as Record<string, unknown> | undefined
    )?.level;
    const stored = (sanitized.thinkingLevel ?? nested) as
      | GeminiThinkingLevel
      | undefined;
    const level = (
      stored != null && thinkingLevelOptions.some((o) => o.value === stored)
        ? stored
        : getDefaultThinkingLevel(model)
    ) as GeminiThinkingLevel;

    // Dropped unconditionally: the field is Opik's own, and no provider accepts it at the top
    // level, so leaving it on the payload can only be dead weight.
    delete sanitized.thinkingLevel;

    // "none" is an explicit "do not think": it has to remove any persisted thinking block, not merely
    // decline to add one, or a level saved earlier keeps being sent and the model keeps thinking.
    if (level === "none") {
      const rest = omit(
        (sanitized.custom_parameters ?? {}) as Record<string, unknown>,
        "thinking",
      );

      if (Object.keys(rest).length > 0) {
        sanitized.custom_parameters = rest;
      } else {
        delete sanitized.custom_parameters;
      }
    }

    // "auto" also sends no thinkingConfig, but it is a weaker statement — "let the model decide" —
    // so it leaves a persisted block alone rather than deleting fields the form cannot represent.
    // `level` is already known to be one this model offers.
    if (
      level !== "auto" &&
      level !== "none" &&
      thinkingLevelOptions.length > 0
    ) {
      const customParameters =
        (sanitized.custom_parameters as Record<string, unknown>) ?? {};
      // Merge into any existing thinking block rather than replacing it — the backend also reads
      // budget_tokens and include_thoughts from there, and only `level` is ours to set here.
      const thinking =
        (customParameters.thinking as Record<string, unknown>) ?? {};

      sanitized.custom_parameters = {
        ...customParameters,
        // An explicit budget outranks the level server-side, so "off" has to clear it. Left in, the
        // block would say "disabled" and "4096 tokens" at once and thinking would stay on.
        thinking:
          level === "off"
            ? { ...omit(thinking, "budget_tokens"), level }
            : { ...thinking, level },
      };
    }
  }

  return sanitized;
};
