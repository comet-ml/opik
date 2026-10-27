import {
  ANTHROPIC_THINKING_EFFORT_VALUES,
  AnthropicEffortLevel,
  AnthropicThinkingEffort,
  COMPOSED_PROVIDER_TYPE,
  GeminiThinkingLevel,
  LLMPromptConfigsType,
  OpenAiPipelineMode,
  OpenAIReasoningEffort,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";
import {
  ANTHROPIC_MODEL_CAPABILITIES,
  DEFAULT_ANTHROPIC_CONFIGS,
  DEFAULT_OPEN_AI_CONFIGS,
  DEFAULT_OPEN_ROUTER_CONFIGS,
  OPENAI_MODEL_CAPABILITIES,
} from "@/constants/llm";
import {
  getProviderFromModel,
  parseComposedProviderType,
} from "@/lib/provider";
import {
  getMaxOutputTokens,
  getOpenRouterMaxOutputTokens,
  isCustomProviderModel,
} from "@/lib/modelCapabilities";
import isPlainObject from "lodash/isPlainObject";
import isEmpty from "lodash/isEmpty";
import omit from "lodash/omit";
import {
  getLatestListedModelParams,
  getLatestModelFlags,
  getLatestProviderModelsSnapshot,
} from "@/lib/modelRegistryStore";
import {
  adjustConfigForModel,
  getEffortLevels,
  getModelParams,
  getNearestOfferedLevel,
  isAccepted,
  OPENAI_PARAM_BINDING,
  resolveEffortLevel,
  resolveMaxOutputTokens,
  resolveSampling,
  SamplingValues,
  toParamsRoute,
} from "@/lib/modelParams";
import { ModelParams } from "@/types/modelParams";
import { PROVIDER_MODELS } from "@/constants/providerModels";
import { snakeCaseObj } from "@/lib/utils";

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
 * An OPENAI_MODEL_CAPABILITIES row wins; otherwise the backend registry flag.
 */
export const isReasoningModel = (model?: PROVIDER_MODEL_TYPE | ""): boolean => {
  if (!model) return false;

  const declared = OPENAI_MODEL_CAPABILITIES[model]?.reasoning;
  if (
    declared !== undefined &&
    getProviderFromModel(model as PROVIDER_MODEL_TYPE) === PROVIDER_TYPE.OPEN_AI
  ) {
    return declared;
  }

  return getLatestModelFlags(model)?.reasoning ?? false;
};

// Which thinking levels each Gemini model accepts, per Google's own support tables
// (https://ai.google.dev/gemini-api/docs/thinking and
// https://cloud.google.com/vertex-ai/generative-ai/docs/thinking — 3.7 Flash, 3.5 Flash and 3.1 Flash
// Lite are only in the Vertex one). The sets genuinely differ per model — 3.7 Flash has no "minimal",
// 3 Pro has only "low" and "high" — and sending a level a model does not accept is rejected
// upstream, so this cannot be collapsed into one list per family.
//
// Keep both provider spellings of a model on the same row: the level support is a property of the
// underlying model, not of whether it is reached through AI Studio or Vertex. A model the catalog
// lists takes its levels from the catalog's curated rows instead; this table is the fallback before the
// model list loads.
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
  [PROVIDER_MODEL_TYPE.GEMINI_3_8_FLASH, LOW_TO_HIGH],
  [PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_8_FLASH, LOW_TO_HIGH],
  [PROVIDER_MODEL_TYPE.GEMINI_3_7_FLASH, LOW_TO_HIGH],
  [PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_7_FLASH, LOW_TO_HIGH],
  [PROVIDER_MODEL_TYPE.GEMINI_3_6_FLASH, MINIMAL_TO_HIGH],
  [PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_6_FLASH, MINIMAL_TO_HIGH],
  [PROVIDER_MODEL_TYPE.GEMINI_3_5_FLASH, MINIMAL_TO_HIGH],
  [PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_5_FLASH, MINIMAL_TO_HIGH],
  [PROVIDER_MODEL_TYPE.GEMINI_3_5_FLASH_LITE, ["off", ...MINIMAL_TO_HIGH]],
  [
    PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_5_FLASH_LITE,
    ["off", ...MINIMAL_TO_HIGH],
  ],
  [PROVIDER_MODEL_TYPE.GEMINI_3_1_PRO, LOW_TO_HIGH],
  [PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_1_PRO, LOW_TO_HIGH],
  // The "minimal, high" row in Google's tables is the separate gemini-3.1-flash-lite-image model.
  [PROVIDER_MODEL_TYPE.GEMINI_3_1_FLASH_LITE, ["off", ...MINIMAL_TO_HIGH]],
  [
    PROVIDER_MODEL_TYPE.GEMINI_3_1_FLASH_LITE_PREVIEW,
    ["off", ...MINIMAL_TO_HIGH],
  ],
  [
    PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_1_FLASH_LITE,
    ["off", ...MINIMAL_TO_HIGH],
  ],
  [
    PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_1_FLASH_LITE_PREVIEW,
    ["off", ...MINIMAL_TO_HIGH],
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
  // 2.5 Pro gets no "off": it cannot disable thinking, and Google answers a zero budget with "Budget 0
  // is invalid. This model only works in thinking mode." 2.5 Flash and Flash Lite both accept it.
  [PROVIDER_MODEL_TYPE.GEMINI_2_5_PRO, ["auto", ...LOW_TO_HIGH]],
  [PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_PRO, ["auto", ...LOW_TO_HIGH]],
  [PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH, ["auto", "off", ...LOW_TO_HIGH]],
  [
    PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_FLASH,
    ["auto", "off", ...LOW_TO_HIGH],
  ],
  [PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH_LITE, ["auto", "off", ...LOW_TO_HIGH]],
  [
    PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_FLASH_LITE_PREVIEW_06_17,
    ["auto", "off", ...LOW_TO_HIGH],
  ],
]);

const THINKING_LEVEL_LABELS: Record<GeminiThinkingLevel, string> = {
  auto: "Auto",
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

// Only an id the Gemini or Vertex AI registry lists: OpenRouter's google/gemini-* and an id no registry
// knows keep the tables. The panel picks its form with this lookup too, never by the prompt's provider,
// so a bare id saved under Vertex AI resolves the same way for the panel and for the request.
export const getGeminiOrVertexListedParams = (
  model?: PROVIDER_MODEL_TYPE | "",
): ModelParams | undefined =>
  getLatestListedModelParams(
    isVertexModel(model) ? PROVIDER_TYPE.VERTEX_AI : PROVIDER_TYPE.GEMINI,
    model,
  );

// The catalog spells a Gemini 3 model's "no thinking" as none, after the OpenAI word; the panel and the
// backend call that level off.
const toGeminiThinkingLevel = (
  level: string,
): GeminiThinkingLevel | undefined => {
  const value = level === "none" ? "off" : level;
  return value in THINKING_LEVEL_LABELS
    ? (value as GeminiThinkingLevel)
    : undefined;
};

const getCatalogThinkingLevels = (params: ModelParams): GeminiThinkingLevel[] =>
  getEffortLevels(params.effort).flatMap((level) => {
    const value = toGeminiThinkingLevel(level);
    return value ? [value] : [];
  });

export const getThinkingLevelLabel = (level: string): string =>
  THINKING_LEVEL_LABELS[level as GeminiThinkingLevel] ?? level;

// An allow-list, so a Gemini generation newer than this list gets no sampling sliders until someone
// checks it: Google asks to keep every Gemini 3 model at its default temperature, and the aliases,
// Omni and Robotics ER ids all resolve to Gemini 3-era models. Only native ids are gated — OpenRouter's
// google/gemini-* and gemma-* fall through, since nothing here describes them.
const SAMPLING_CAPABLE_GEMINI_GENERATIONS =
  /^gemini-(?:1\.0|1\.5|2\.0|2\.5)(?:-|$)/;
const SAMPLING_CAPABLE_UNVERSIONED_GEMINI_IDS: ReadonlySet<string> = new Set([
  "gemini-pro-vision",
]);

export const supportsGeminiSamplingParams = (
  model?: PROVIDER_MODEL_TYPE | "",
): boolean => {
  const params = getGeminiOrVertexListedParams(model);
  if (params) {
    return isAccepted(params.temperature) || isAccepted(params.top_p);
  }
  const id = (model ?? "").replace(/^vertex_ai\//, "");
  if (!id.startsWith("gemini-")) {
    return true;
  }
  return (
    SAMPLING_CAPABLE_GEMINI_GENERATIONS.test(id) ||
    SAMPLING_CAPABLE_UNVERSIONED_GEMINI_IDS.has(id)
  );
};

/**
 * Checks if a Gemini model supports thinking level parameter
 *
 * @param model - The model type to check
 * @returns true if the model supports thinking level, false otherwise
 */
export const supportsGeminiThinkingLevel = (
  model?: PROVIDER_MODEL_TYPE | "",
): boolean =>
  !isVertexModel(model) && getThinkingLevelOptions(model).length > 0;

/**
 * Checks if a Vertex AI model supports thinking level parameter
 *
 * @param model - The model type to check
 * @returns true if the model supports thinking level, false otherwise
 */
export const supportsVertexAIThinkingLevel = (
  model?: PROVIDER_MODEL_TYPE | "",
): boolean => isVertexModel(model) && getThinkingLevelOptions(model).length > 0;

/**
 * The thinking levels a model accepts, as select options. Empty for models without thinking.
 */
export const getThinkingLevelOptions = (
  model?: PROVIDER_MODEL_TYPE | "",
): Array<{ label: string; value: GeminiThinkingLevel }> => {
  const params = getGeminiOrVertexListedParams(model);
  const levels = params
    ? getCatalogThinkingLevels(params)
    : THINKING_LEVELS_BY_MODEL.get(model as PROVIDER_MODEL_TYPE) ?? [];
  return levels.map((value) => ({
    label: THINKING_LEVEL_LABELS[value],
    value,
  }));
};

// Each model's own default thinking level, as Google's two tables state it. Preselecting it keeps
// the control from silently changing a model's behaviour just by being shown: 2.5 Flash Lite ships
// with thinking off (AI Studio's table; Vertex's says Auto), 2.5 Pro/Flash default to a dynamic
// budget ("auto"), 3.8/3.7/3.6/3.5 Flash to medium — none of which is "high". The 3.x Flash Lite
// models show Off: Google lists their default as minimal, but they spend no thinking tokens on it, so
// Off is what a user sees them do. Models absent here default to "high", which is what the Gemini 3
// Pro and 3 Flash rows document.
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
  [PROVIDER_MODEL_TYPE.GEMINI_3_8_FLASH, "medium" as GeminiThinkingLevel],
  [
    PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_8_FLASH,
    "medium" as GeminiThinkingLevel,
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
  [PROVIDER_MODEL_TYPE.GEMINI_3_5_FLASH_LITE, "off" as GeminiThinkingLevel],
  [
    PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_5_FLASH_LITE,
    "off" as GeminiThinkingLevel,
  ],
  [PROVIDER_MODEL_TYPE.GEMINI_3_1_FLASH_LITE, "off" as GeminiThinkingLevel],
  [
    PROVIDER_MODEL_TYPE.GEMINI_3_1_FLASH_LITE_PREVIEW,
    "off" as GeminiThinkingLevel,
  ],
  [
    PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_1_FLASH_LITE,
    "off" as GeminiThinkingLevel,
  ],
  [
    PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_1_FLASH_LITE_PREVIEW,
    "off" as GeminiThinkingLevel,
  ],
]);

/**
 * The level to preselect: the model's own documented default, so showing the control does not
 * change how the model behaves.
 */
export const getDefaultThinkingLevel = (
  model?: PROVIDER_MODEL_TYPE | "",
): GeminiThinkingLevel => {
  const params = getGeminiOrVertexListedParams(model);
  const level = params ? resolveEffortLevel(params.effort, []) : undefined;
  return (
    (level ? toGeminiThinkingLevel(level) : undefined) ??
    DEFAULT_THINKING_LEVEL_BY_MODEL.get(model as PROVIDER_MODEL_TYPE) ??
    "high"
  );
};

// Vertex AI answers a value above the model's limit with a 400 ("the supported range is from 1
// (inclusive) to 65537 (exclusive)"), so both providers send at most the catalog's limit. 0 still
// means no limit and is left out.
export const resolveGeminiMaxOutputTokens = (
  model: PROVIDER_MODEL_TYPE | "",
  value: number,
): number => {
  const limit = getGeminiOrVertexListedParams(model)?.max_output_tokens;
  return isAccepted(limit) && limit?.max !== undefined
    ? Math.min(value, limit.max)
    : value;
};

export type ThinkingLevelParams = {
  thinkingLevel?: GeminiThinkingLevel;
  custom_parameters?: unknown;
};

// What the Gemini and Vertex AI panels show and what the request sends. A
// level the model does not offer (saved for another model, or by older code)
// falls back to the model's default, the same way resolveEffort does.
export const resolveThinkingLevel = (
  model: PROVIDER_MODEL_TYPE | "",
  configs: ThinkingLevelParams,
): GeminiThinkingLevel => {
  const nested = (
    (configs.custom_parameters as Record<string, unknown> | undefined)
      ?.thinking as Record<string, unknown> | undefined
  )?.level;
  const stored = configs.thinkingLevel ?? nested;
  return getThinkingLevelOptions(model).some((o) => o.value === stored)
    ? (stored as GeminiThinkingLevel)
    : getDefaultThinkingLevel(model);
};

// A model switch has to carry the level the panel showed. Carrying a hidden stored one (a "minimal"
// shown as Auto on Gemini 2.5 Flash) brings it back on the next model that offers it.
export const withShownThinkingLevel = <T extends object>(
  model: PROVIDER_MODEL_TYPE | "",
  configs: T,
): T => {
  if (getThinkingLevelOptions(model).length === 0) {
    return configs;
  }
  const params = configs as ThinkingLevelParams;
  const shown = resolveThinkingLevel(model, params);
  return params.thinkingLevel === shown
    ? configs
    : ({ ...configs, thinkingLevel: shown } as T);
};

// "auto" leaves how much to think to the model, so it drops both ways of setting that: the level and
// a budget_tokens set through the API, which outranks any level server-side. include_thoughts and the
// other keys stay, as they do for "off".
export const withoutThinkingAmount = (
  customParameters: unknown,
): Record<string, unknown> | undefined => {
  const params = (customParameters ?? {}) as Record<string, unknown>;
  const thinking = isPlainObject(params.thinking)
    ? (params.thinking as Record<string, unknown>)
    : undefined;
  if (!thinking || !("level" in thinking || "budget_tokens" in thinking)) {
    return isEmpty(params) ? undefined : params;
  }
  const restThinking = omit(thinking, ["level", "budget_tokens"]);
  const rest = isEmpty(restThinking)
    ? omit(params, "thinking")
    : { ...params, thinking: restThinking };
  return isEmpty(rest) ? undefined : rest;
};

// Gemini 3 and later cannot turn thinking off (the backend drops an "off" for them too), so their Off
// can only mean the model's own default, and it is left off the request like "auto". Sending the
// documented MINIMAL instead is not harmless: Vertex answers it in a multi-turn prompt without
// thought signatures with a 400, and a hand-written assistant message has none.
const GEMINI_MAJOR_VERSION_PATTERN = /gemini-(\d{1,3})(?=[.-])/;

const cannotTurnThinkingOff = (model: PROVIDER_MODEL_TYPE | ""): boolean => {
  const match = GEMINI_MAJOR_VERSION_PATTERN.exec(model);
  return !!match && Number(match[1]) >= 3;
};

export const leavesThinkingToModel = (
  model: PROVIDER_MODEL_TYPE | "",
  level: unknown,
): boolean =>
  level === "auto" || (level === "off" && cannotTurnThinkingOff(model));

const THINKING_LEVEL_DESCRIPTION =
  "Controls the depth of reasoning the model performs before responding. Higher thinking level may result in more thorough but slower responses.";

export const getThinkingLevelDescription = (
  model: PROVIDER_MODEL_TYPE | "",
): string =>
  leavesThinkingToModel(model, "off") &&
  getThinkingLevelOptions(model).some((o) => o.value === "off")
    ? `${THINKING_LEVEL_DESCRIPTION} Off sends no thinking setting: Google lists this model's default as minimal, and it spends almost no thinking tokens on it.`
    : THINKING_LEVEL_DESCRIPTION;

const EFFORT_LABELS: Record<AnthropicThinkingEffort, string> = {
  off: "Off",
  low: "Low",
  medium: "Medium",
  high: "High",
  xhigh: "xHigh",
  max: "Max",
};

// Only an id the Anthropic registry lists: these helpers take no provider, and a Bedrock or OpenRouter
// spelling of the same Claude must keep the canonical-id rules below.
const getAnthropicListedParams = (
  model?: PROVIDER_MODEL_TYPE | "",
): ModelParams | undefined =>
  getLatestListedModelParams(PROVIDER_TYPE.ANTHROPIC, model);

const toAnthropicEffort = (
  level: unknown,
): AnthropicThinkingEffort | undefined =>
  ANTHROPIC_THINKING_EFFORT_VALUES.find((value) => value === level);

// Derived from the capability map so the two cannot drift.
const SAMPLING_CAPABLE_MODELS = Object.entries(ANTHROPIC_MODEL_CAPABILITIES)
  .filter(([, capabilities]) => capabilities?.supportsSamplingParams)
  .map(([model]) => model);

/**
 * The generations that predate the constraint, recognised by shape rather than listed: they all take
 * sampling params. Claude 3 and earlier spell the version before the family (`claude-3-5-sonnet`) or
 * omit it (`claude-instant`); Claude 4 onward spells it after (`claude-sonnet-4-5`). The backend
 * applies the same set.
 */
const LEGACY_GENERATION_PREFIXES = [
  "claude-2",
  "claude-3",
  "claude-v2",
  "claude-instant",
];

const LATEST_ALIAS_SUFFIX = "-latest";
const RELEASE_DATE = /^\d{8}$/;

// The union of both lists, because neither alone is the set of Anthropic models we know: the
// dropdown omits ids that are still reachable through Bedrock and proxies, and the capability map
// only names the ones that take sampling params. Missing an id here no longer means "assume
// permissive" — it means the model is treated as taking none, so the set has to be complete.
// The prefix has to end where a segment does, or `claude-30-future` would read as Claude 3.
const isLegacyGeneration = (canonical: string): boolean =>
  LEGACY_GENERATION_PREFIXES.some(
    (prefix) => canonical === prefix || canonical.startsWith(`${prefix}-`),
  );

const familyToken = (id: string): string =>
  id.replace(/^claude-/, "").split("-")[0];

const KNOWN_ANTHROPIC_MODELS = Array.from(
  new Set([
    ...(PROVIDER_MODELS[PROVIDER_TYPE.ANTHROPIC] ?? []).map(
      (model) => model.value as string,
    ),
    ...Object.keys(ANTHROPIC_MODEL_CAPABILITIES),
  ]),
);

/**
 * The family words Anthropic actually ships, read off the known ids so a sync that adds a family adds
 * it here too. A name whose family we do not recognise is not treated as an Anthropic id at all:
 * `claude-prod` behind a gateway is a deployment someone named, and says nothing about which Claude
 * is serving it, so it keeps the sampling params set on it.
 */
const FAMILY_TOKENS = new Set(
  KNOWN_ANTHROPIC_MODELS.map(familyToken).filter(
    (token) => !/^\d+$/.test(token),
  ),
);

const namesAnAnthropicModel = (id: string): boolean =>
  isLegacyGeneration(id) || FAMILY_TOKENS.has(familyToken(id));

/**
 * Comparable version segments, with any release date dropped so `claude-opus-4-6` outranks
 * `claude-opus-4-20250514` instead of losing to the larger number.
 */
const versionKey = (id: string): string =>
  id
    .split("-")
    .filter((segment) => /^\d+$/.test(segment) && !RELEASE_DATE.test(segment))
    .map((segment) => segment.padStart(4, "0"))
    .join(".");

/**
 * The newest known member of a family, which is what `claude-opus-latest` names. A floating alias has
 * to be read as the model it currently resolves to: while Haiku 4.5 was the newest Haiku,
 * `claude-haiku-latest` took sampling params, and the same model under its own id already said so.
 */
const newestInFamily = (familyPrefix: string): string | undefined =>
  KNOWN_ANTHROPIC_MODELS.filter((id) => id.startsWith(`${familyPrefix}-`)).sort(
    (a, b) => versionKey(b).localeCompare(versionKey(a)),
  )[0];

/**
 * The Anthropic id a routed model name denotes, when we know it.
 *
 * One model arrives spelled three ways: Anthropic's own `claude-opus-4-6`, Bedrock's
 * `us.anthropic.claude-sonnet-4-5-20250929-v1:0` and OpenRouter's dotted `anthropic/claude-opus-4.7`.
 * Reducing all three to the bare id lets the match be anchored at the start rather than found
 * anywhere in the string, and the longest match wins so a later `claude-opus-4-9` reads as itself
 * rather than as the `claude-opus-4` it begins with. The backend canonicalizes identically.
 */
const canonicalAnthropicId = (model: string): string => {
  const segment = (model.split("/").pop() ?? "")
    .trim()
    .toLowerCase()
    .replace(/\./g, "-");
  const claudeAt = segment.indexOf("claude-");
  if (claudeAt < 0) {
    return "";
  }
  // Only a vendor decoration may precede the id: Bedrock's region and vendor prefix says which
  // Claude this is, whereas a proxy's own name for a model it renamed (my-claude-deployment) does
  // not, and must keep the sampling params someone set on it.
  const prefix = segment.slice(0, claudeAt);
  if (prefix && !prefix.endsWith("anthropic-")) {
    return "";
  }
  // Bedrock appends an inference profile (-v1:0); OpenRouter, a :free or :beta variant. Never strip
  // down to the bare family word: `claude-v2` is Claude 2, not a decorated `claude`.
  const bare = segment.slice(claudeAt).split(":")[0];
  const stripped = bare.replace(/-v\d+$/, "");
  const id = stripped.includes("-") ? stripped : bare;

  return namesAnAnthropicModel(id) ? id : "";
};

// A prefix names the model only when it ends where a segment does, so `claude-opus-4-1` is not
// `claude-opus-4`. A numeric segment is the next version rather than a variant of this one — an
// unlisted `claude-sonnet-4-6-1` must not inherit `claude-sonnet-4-6`'s capability — while a named
// variant (`-fast`) and a release date still name the same model.
const namesModel = (canonical: string, id: string): boolean => {
  if (canonical === id) {
    return true;
  }
  if (canonical.startsWith(`${id}-`)) {
    const next = canonical.slice(id.length + 1).split("-")[0];
    return RELEASE_DATE.test(next) || !/^\d+$/.test(next);
  }
  return (
    id.startsWith(`${canonical}-`) &&
    RELEASE_DATE.test(id.slice(canonical.length + 1))
  );
};

const knownAnthropicId = (canonical: string): string | undefined => {
  if (canonical.endsWith(LATEST_ALIAS_SUFFIX)) {
    return newestInFamily(canonical.slice(0, -LATEST_ALIAS_SUFFIX.length));
  }
  return KNOWN_ANTHROPIC_MODELS.filter((id) => namesModel(canonical, id)).sort(
    (a, b) => b.length - a.length,
  )[0];
};

/**
 * Whether the model accepts temperature/top_p at all.
 *
 * The capability map names the models that do, so an Anthropic id without a row is assumed to take
 * none — newer ones increasingly don't, and an unplaceable id is far more often a model newer than
 * this list than an older one missing from it. The two failures are not equal: assuming it takes none
 * omits a parameter, while assuming it takes them fails the whole request.
 *
 * Two exceptions stay permissive. The generations before Claude 4 predate the constraint entirely,
 * and a name that is not an Anthropic id at all may be a capable Claude a proxy renamed.
 *
 * Matching goes through knownAnthropicId, because the same models arrive through Bedrock and
 * OpenAI-compatible proxies under prefixed, dotted and dated ids.
 */
export const supportsSamplingParams = (
  model?: PROVIDER_MODEL_TYPE | "",
): boolean => {
  if (!model) {
    return true;
  }

  const params = getAnthropicListedParams(model);
  if (params) {
    return isAccepted(params.temperature) || isAccepted(params.top_p);
  }

  const declared =
    ANTHROPIC_MODEL_CAPABILITIES[model as PROVIDER_MODEL_TYPE]
      ?.supportsSamplingParams;
  if (declared !== undefined) {
    return declared;
  }

  const canonical = canonicalAnthropicId(model);
  // Not an Anthropic id, or the generation that predates the constraint: leave it alone.
  if (!canonical || isLegacyGeneration(canonical)) {
    return true;
  }

  const known = knownAnthropicId(canonical);
  return known !== undefined && SAMPLING_CAPABLE_MODELS.includes(known);
};

export const supportsAnthropicThinkingEffort = (
  model?: PROVIDER_MODEL_TYPE | "",
): boolean => {
  const params = getAnthropicListedParams(model);
  return params
    ? getEffortLevels(params.effort).length > 0
    : !!ANTHROPIC_MODEL_CAPABILITIES[model as PROVIDER_MODEL_TYPE]
        ?.thinkingEffortOptions;
};

// A Claude model with no row is one this build does not know yet. The backend checks its effort only
// against the known level names and lets Anthropic judge the rest, so a stored level must pass through
// rather than be dropped as unsupported. A row without thinkingEffortOptions means the model takes none.
export const knowsAnthropicEffortLevels = (
  model?: PROVIDER_MODEL_TYPE | "",
): boolean =>
  !!getAnthropicListedParams(model) ||
  !!ANTHROPIC_MODEL_CAPABILITIES[model as PROVIDER_MODEL_TYPE];

export const thinksOnlyWhenAsked = (
  model?: PROVIDER_MODEL_TYPE | "",
): boolean =>
  !!ANTHROPIC_MODEL_CAPABILITIES[model as PROVIDER_MODEL_TYPE]
    ?.thinksOnlyWhenAsked;

export const hasEffortWithoutThinking = (
  model?: PROVIDER_MODEL_TYPE | "",
): boolean =>
  !!ANTHROPIC_MODEL_CAPABILITIES[model as PROVIDER_MODEL_TYPE]
    ?.effortWithoutThinking;

// Off for a model that thinks only when asked: that is how it runs when the request names no
// effort, so a new prompt or rule does not start thinking until someone picks a level.
export const getDefaultThinkingEffort = (
  model?: PROVIDER_MODEL_TYPE | "",
): AnthropicThinkingEffort => {
  const params = thinksOnlyWhenAsked(model)
    ? undefined
    : getAnthropicListedParams(model);
  const level = params ? resolveEffortLevel(params.effort, []) : undefined;
  return (
    toAnthropicEffort(level) ??
    ANTHROPIC_MODEL_CAPABILITIES[model as PROVIDER_MODEL_TYPE]
      ?.defaultThinkingEffort ??
    (thinksOnlyWhenAsked(model) ? "off" : "high")
  );
};

const asRecord = (value: unknown): Record<string, unknown> =>
  isPlainObject(value) ? (value as Record<string, unknown>) : {};

/**
 * The effort a config holds under custom_parameters.output_config, which is where the request
 * carries it. Surfaces that store the request shape and load it back (a saved optimization run, an
 * evaluator rule) have only this copy.
 */
export const getNestedThinkingEffort = (
  customParameters: unknown,
): AnthropicEffortLevel | undefined => {
  const effort = asRecord(asRecord(customParameters).output_config).effort;
  return ANTHROPIC_THINKING_EFFORT_VALUES.find((level) => level === effort);
};

// Keeps every other key, inside output_config too, so fields no form control shows survive a save.
export const withThinkingEffort = (
  customParameters: unknown,
  effort: AnthropicThinkingEffort | undefined,
): Record<string, unknown> | undefined => {
  const params = asRecord(customParameters);
  // Rebuilding a non-object output_config would turn it into a valid effort-only one and hide the
  // backend's 400, so a malformed value is sent as it is.
  if (params.output_config != null && !isPlainObject(params.output_config)) {
    return Object.keys(params).length > 0 ? params : undefined;
  }
  const outputConfig = omit(asRecord(params.output_config), "effort");
  const nextOutputConfig =
    effort && effort !== "off" ? { ...outputConfig, effort } : outputConfig;
  const next =
    Object.keys(nextOutputConfig).length > 0
      ? { ...omit(params, "output_config"), output_config: nextOutputConfig }
      : omit(params, "output_config");
  return Object.keys(next).length > 0 ? next : undefined;
};

export const getAnthropicThinkingEffortLabel = (level: string): string =>
  EFFORT_LABELS[level as AnthropicThinkingEffort] ?? level;

export const getAnthropicThinkingEffortOptions = (
  model?: PROVIDER_MODEL_TYPE | "",
): Array<{ label: string; value: AnthropicThinkingEffort }> => {
  const params = getAnthropicListedParams(model);
  const levels: readonly AnthropicThinkingEffort[] = params
    ? getEffortLevels(params.effort).flatMap((level) => {
        const effort = toAnthropicEffort(level);
        return effort ? [effort] : [];
      })
    : supportsAnthropicThinkingEffort(model)
      ? ANTHROPIC_MODEL_CAPABILITIES[model as PROVIDER_MODEL_TYPE]
          ?.thinkingEffortOptions ?? []
      : [];
  return [
    ...(levels.length > 0 && thinksOnlyWhenAsked(model)
      ? (["off"] as const)
      : []),
    ...levels,
  ].map((value) => ({ label: EFFORT_LABELS[value], value }));
};

const OPENAI_EFFORT_LABELS: Record<OpenAIReasoningEffort, string> = {
  none: "None",
  minimal: "Minimal",
  low: "Low",
  medium: "Medium",
  high: "High",
  xhigh: "xHigh",
  max: "Max",
};

export const toOpenAIReasoningEffort = (
  level: unknown,
): OpenAIReasoningEffort | undefined =>
  typeof level === "string" && Object.keys(OPENAI_EFFORT_LABELS).includes(level)
    ? (level as OpenAIReasoningEffort)
    : undefined;

export const getOpenAIReasoningEffortLabel = (level: string): string =>
  OPENAI_EFFORT_LABELS[level as OpenAIReasoningEffort] ?? level;

// Only an id the OpenAI registry lists, read on the key's route: getProviderFromModel also answers
// OpenAI for a custom gateway's ids, and those keep the rules below.
const getOpenAIListedParams = (
  model?: PROVIDER_MODEL_TYPE | "",
  openAiPipelineMode?: OpenAiPipelineMode,
): ModelParams | undefined => {
  const params = getLatestListedModelParams(PROVIDER_TYPE.OPEN_AI, model);
  const route = toParamsRoute(openAiPipelineMode);
  return (route ? params?.routes?.[route] : undefined) ?? params;
};

// A level the sync adds before this frontend has a name for it is left out everywhere: the request
// cannot send it, so the panel must not offer or show it either.
export const getOpenAIEffortLevels = (
  params?: ModelParams,
): OpenAIReasoningEffort[] =>
  getEffortLevels(params?.effort).flatMap((level) => {
    const effort = toOpenAIReasoningEffort(level);
    return effort ? [effort] : [];
  });

export const resolveOpenAIEffort = (
  params: ModelParams,
  stored: unknown,
): OpenAIReasoningEffort | undefined =>
  toOpenAIReasoningEffort(
    resolveEffortLevel(params.effort, [stored], getOpenAIEffortLevels(params)),
  );

export const supportsOpenAIReasoningEffort = (
  model?: PROVIDER_MODEL_TYPE | "",
): boolean => {
  const params = getOpenAIListedParams(model);
  return params
    ? getOpenAIEffortLevels(params).length > 0
    : !!OPENAI_MODEL_CAPABILITIES[model as PROVIDER_MODEL_TYPE]
        ?.reasoningEffortOptions;
};

export const getDefaultReasoningEffort = (
  model?: PROVIDER_MODEL_TYPE | "",
): OpenAIReasoningEffort =>
  OPENAI_MODEL_CAPABILITIES[model as PROVIDER_MODEL_TYPE]
    ?.defaultReasoningEffort ?? "medium";

// An unknown pipeline mode (keys still loading, a surface that never reaches
// Opik's OpenAI pipeline) is treated as Chat Completions, the backend's own
// default: offering a Responses-only value there would 400.
export const getOpenAIReasoningEffortOptions = (
  model?: PROVIDER_MODEL_TYPE | "",
  openAiPipelineMode?: OpenAiPipelineMode,
): Array<{ label: string; value: OpenAIReasoningEffort }> => {
  const params = getOpenAIListedParams(model, openAiPipelineMode);
  if (params) {
    return getOpenAIEffortLevels(params).map((value) => ({
      label: OPENAI_EFFORT_LABELS[value],
      value,
    }));
  }
  const capabilities = OPENAI_MODEL_CAPABILITIES[model as PROVIDER_MODEL_TYPE];
  const responsesApiOnly =
    openAiPipelineMode === "responses_api"
      ? capabilities?.responsesApiOnlyEffortOptions ?? []
      : [];

  return [
    ...(capabilities?.reasoningEffortOptions ?? []),
    ...responsesApiOnly,
  ].map((value) => ({ label: OPENAI_EFFORT_LABELS[value], value }));
};

// Single reconciler called by every model-change handler (playground, judge
// dialog). Keeping the rules here means the form state stays valid even when
// the user switches models without opening the config dropdown.
// An effort or thinking level equal to previousModel's default was never picked
// by the user, so the next model's own default replaces it: otherwise Sonnet
// 4.6's high would override Opus 5.5's medium. A picked level the next model
// does not offer moves to the nearest one it does, rather than to the default:
// Minimal to Low on GPT 5.1, not High. Without previousModel (a loaded config
// being normalised) a stored value the model offers is kept, and one it does
// not offer becomes the model's default.
export const updateProviderConfig = <
  T extends {
    temperature?: number;
    topP?: number;
    thinkingEffort?: AnthropicThinkingEffort;
    reasoningEffort?: OpenAIReasoningEffort;
    thinkingLevel?: GeminiThinkingLevel;
  },
>(
  currentConfig: T | undefined,
  params: {
    model: PROVIDER_MODEL_TYPE | "";
    provider: COMPOSED_PROVIDER_TYPE;
    openAiPipelineMode?: OpenAiPipelineMode;
    previousModel?: PROVIDER_MODEL_TYPE | "";
  },
): T | undefined => {
  if (!currentConfig) {
    return currentConfig;
  }

  const providerType = parseComposedProviderType(params.provider);

  if (providerType === PROVIDER_TYPE.OPEN_AI) {
    const modelParams = getModelParams(
      PROVIDER_TYPE.OPEN_AI,
      params.model,
      toParamsRoute(params.openAiPipelineMode ?? "responses_api"),
    );
    if (modelParams) {
      return adjustConfigForModel(
        currentConfig,
        modelParams,
        OPENAI_PARAM_BINDING,
        params.previousModel
          ? getModelParams(
              PROVIDER_TYPE.OPEN_AI,
              params.previousModel,
              toParamsRoute(params.openAiPipelineMode ?? "responses_api"),
            )
          : undefined,
        getOpenAIEffortLevels,
      );
    }

    const next: T = { ...currentConfig };
    let changed = false;

    // reasoningEffort: drop it for models without an effort option list,
    // otherwise move a stale value to the nearest offered level on a switch,
    // and to the model's default when normalising a loaded config. Mirrors the Anthropic
    // thinkingEffort handling below. Unlike resolveEffort, which only masks a
    // max the key cannot take, this writes the coerced value back on purpose:
    // a model change settles on a level the new model and mode offer, so a key
    // later moved back to the Responses API restores max only on prompts whose
    // model never changed.
    // An unknown mode (keys still loading) is checked against the Responses
    // API list, a superset of the Chat Completions one, so a stored max is
    // kept: assuming Chat Completions here would rewrite it to the model's
    // default for good.
    // The previous model's list is the one its panel showed, which reads an
    // unknown mode as Chat Completions: a max shown there as the default
    // follows the next model's default rather than moving to xhigh.
    const mode = params.openAiPipelineMode ?? "responses_api";
    const effortOptions = getOpenAIReasoningEffortOptions(params.model, mode);
    const previousEffortOptions = getOpenAIReasoningEffortOptions(
      params.previousModel,
      params.openAiPipelineMode,
    );
    const previousDefault =
      previousEffortOptions.length > 0
        ? getDefaultReasoningEffort(params.previousModel)
        : undefined;
    if (effortOptions.length === 0) {
      if (next.reasoningEffort !== undefined) {
        next.reasoningEffort = undefined;
        changed = true;
      }
    } else if (
      next.reasoningEffort !== undefined &&
      (!effortOptions.some((o) => o.value === next.reasoningEffort) ||
        next.reasoningEffort === previousDefault)
    ) {
      const settled =
        (next.reasoningEffort === previousDefault
          ? undefined
          : getNearestOfferedLevel(
              next.reasoningEffort,
              effortOptions,
              previousEffortOptions,
            )) ?? getDefaultReasoningEffort(params.model);
      if (next.reasoningEffort !== settled) {
        next.reasoningEffort = settled;
        changed = true;
      }
    }

    return changed ? next : currentConfig;
  }

  if (providerType === PROVIDER_TYPE.ANTHROPIC) {
    const next: T = { ...currentConfig };
    let changed = false;

    const effortOptions = getAnthropicThinkingEffortOptions(params.model);
    const previousEffortOptions = getAnthropicThinkingEffortOptions(
      params.previousModel,
    );
    const previousDefault =
      previousEffortOptions.length > 0
        ? getDefaultThinkingEffort(params.previousModel)
        : undefined;
    if (effortOptions.length === 0) {
      if (next.thinkingEffort !== undefined) {
        next.thinkingEffort = undefined;
        changed = true;
      }
    } else if (
      next.thinkingEffort !== undefined &&
      (!effortOptions.some((o) => o.value === next.thinkingEffort) ||
        next.thinkingEffort === previousDefault)
    ) {
      const settled =
        (next.thinkingEffort === previousDefault
          ? undefined
          : getNearestOfferedLevel(
              next.thinkingEffort,
              effortOptions,
              previousEffortOptions,
            )) ?? getDefaultThinkingEffort(params.model);
      if (next.thinkingEffort !== settled) {
        next.thinkingEffort = settled;
        changed = true;
      }
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
    const previousLevelOptions = getThinkingLevelOptions(params.previousModel);
    const previousDefault =
      previousLevelOptions.length > 0
        ? getDefaultThinkingLevel(params.previousModel)
        : undefined;
    if (levelOptions.length === 0) {
      if (next.thinkingLevel !== undefined) {
        next.thinkingLevel = undefined;
        changed = true;
      }
    } else if (
      !levelOptions.some((o) => o.value === next.thinkingLevel) ||
      next.thinkingLevel === previousDefault
    ) {
      const settled =
        (next.thinkingLevel === previousDefault
          ? undefined
          : getNearestOfferedLevel(
              next.thinkingLevel,
              levelOptions,
              previousLevelOptions,
            )) ?? getDefaultThinkingLevel(params.model);
      if (next.thinkingLevel !== settled) {
        next.thinkingLevel = settled;
        changed = true;
      }
    }

    return changed ? next : currentConfig;
  }

  return currentConfig;
};

export type SamplingParams = { temperature?: number; topP?: number };

/**
 * Whether a model is an Anthropic Claude model, whatever provider is serving it.
 *
 * The family name is the only signal common to every route: Anthropic's own ids
 * (`claude-opus-4-6`), Bedrock's decorated ids (`us.anthropic.claude-…-v1:0`), OpenRouter's
 * (`anthropic/claude-…`) and whatever an OpenAI-compatible proxy is configured to call them.
 *
 * Only the last segment is matched, because a custom id carries the gateway in its prefix
 * (`custom-llm/<provider_name>/<model>`) — a provider someone called "claude-gw" must not make
 * every model behind it, Mistral included, look like Claude and lose its Top P.
 */
export const isClaudeModel = (model: PROVIDER_MODEL_TYPE | ""): boolean =>
  /claude/i.test((model.split("/").pop() ?? "").trim());

// OpenRouter names OpenAI's and Google's models `<vendor>/<native id>`, with routing variants after a
// colon (`:free`, `:batch`). The native id is used only when that provider's list has it: ids only
// OpenRouter uses (o3-mini-high, gpt-5-chat) have no row to read, and guessing would hide working sliders.
const OPEN_ROUTER_NATIVE_ID_PATTERN = /^(openai|google)\/([^:]+)/;

const getNativeModelBehindOpenRouter = (
  model: PROVIDER_MODEL_TYPE,
): PROVIDER_MODEL_TYPE | undefined => {
  const match = OPEN_ROUTER_NATIVE_ID_PATTERN.exec(model);
  if (!match) {
    return undefined;
  }
  const [, vendor, id] = match;
  const nativeProvider =
    vendor === "openai" ? PROVIDER_TYPE.OPEN_AI : PROVIDER_TYPE.GEMINI;
  const listed = (getLatestProviderModelsSnapshot()[nativeProvider] ?? []).some(
    (option) => option.value === id,
  );
  return listed ? (id as PROVIDER_MODEL_TYPE) : undefined;
};

const OPEN_ROUTER_PARAM_NAMES = {
  temperature: ["temperature"],
  topP: ["top_p"],
  maxTokens: ["max_tokens", "max_completion_tokens"],
  topK: ["top_k"],
  frequencyPenalty: ["frequency_penalty"],
  presencePenalty: ["presence_penalty"],
  repetitionPenalty: ["repetition_penalty"],
  minP: ["min_p"],
  topA: ["top_a"],
} as const;

export type OpenRouterParam = keyof typeof OPEN_ROUTER_PARAM_NAMES;

// OpenRouter answers 200 and drops a parameter the model does not list. No list (a registry copy
// synced before it was carried, an id OpenRouter no longer serves) keeps every control, as before.
export const supportsOpenRouterParam = (
  model: PROVIDER_MODEL_TYPE | "" | undefined,
  param: OpenRouterParam,
): boolean => {
  const supported = getLatestModelFlags(model)?.supportedParameters;
  return (
    !supported ||
    OPEN_ROUTER_PARAM_NAMES[param].some((name) => supported.includes(name))
  );
};

const OPEN_ROUTER_EFFORT_ORDER: readonly OpenAIReasoningEffort[] = [
  "none",
  "minimal",
  "low",
  "medium",
  "high",
  "xhigh",
  "max",
];

// OpenRouter publishes each model's levels. It publishes none for OpenAI's o-series, which still
// take OpenAI's own levels through it.
export const getOpenRouterReasoningEffortOptions = (
  model?: PROVIDER_MODEL_TYPE | "",
): Array<{ label: string; value: OpenAIReasoningEffort }> => {
  const flags = getLatestModelFlags(model);
  const takesReasoning = flags?.supportedParameters?.some(
    (name) => name === "reasoning" || name === "reasoning_effort",
  );
  if (!model || !takesReasoning) {
    return [];
  }

  const listed = flags?.reasoningEfforts ?? [];
  if (listed.length === 0) {
    const native = getNativeModelBehindOpenRouter(model);
    return native ? getOpenAIReasoningEffortOptions(native) : [];
  }

  return OPEN_ROUTER_EFFORT_ORDER.filter((value) => listed.includes(value)).map(
    (value) => ({ label: OPENAI_EFFORT_LABELS[value], value }),
  );
};

/**
 * The single interpreter of temperature/topP for a model: capability gating plus Anthropic's
 * temperature-XOR-topP rule.
 *
 * The settings panel and the request builder both read through it, so a slider can never show a
 * value the request leaves out. That lets the stored config keep whatever the user last chose even
 * while a model that rejects it is selected — switching back restores the value instead of losing
 * it.
 *
 * It gates and disambiguates; it does not invent. A parameter the config does not carry stays
 * absent, because the same panels serve surfaces with narrower configs — the LLM judge rule stores
 * no topP, so offering one there would show a control its save path drops. Filling in a parameter a
 * surface genuinely owns belongs to that surface (see restoreMissingConfigKeys for the playground).
 */
export const resolveSamplingParams = (
  model: PROVIDER_MODEL_TYPE | "",
  configs: { temperature?: number | null; topP?: number | null } & EffortParams,
): SamplingParams => {
  let temperature = configs.temperature ?? undefined;
  let topP = configs.topP ?? undefined;

  if (!model) {
    return { temperature, topP };
  }

  // Some Claude models refuse both outright. Checked ahead of the provider branches because it
  // holds wherever the model is served from, not only under the Anthropic provider.
  if (isClaudeModel(model) && !supportsSamplingParams(model)) {
    return {};
  }

  const provider = getProviderFromModel(model as PROVIDER_MODEL_TYPE);

  if (provider === PROVIDER_TYPE.OPEN_ROUTER) {
    if (!supportsOpenRouterParam(model, "temperature")) {
      temperature = undefined;
    }
    if (!supportsOpenRouterParam(model, "topP")) {
      topP = undefined;
    }
    const native = getNativeModelBehindOpenRouter(model);
    if (native) {
      return resolveSamplingParams(native, { temperature, topP });
    }
  }

  if (provider === PROVIDER_TYPE.ANTHROPIC) {
    if (isAnthropicThinkingRequested(model, configs)) {
      return {};
    }
    // Anthropic takes one of the pair, never both: temperature wins a config carrying both, and
    // takes over when neither is set so the panel can't offer two live sliders.
    if (temperature !== undefined) {
      return { temperature };
    }
    if (topP !== undefined) {
      return { topP };
    }
    return { temperature: DEFAULT_ANTHROPIC_CONFIGS.TEMPERATURE };
  }

  if (provider === PROVIDER_TYPE.OPEN_AI) {
    const params = getOpenAIListedParams(model);
    if (params) {
      return resolveSampling(params, configs);
    }
  }

  // Reasoning models take neither: top_p is rejected outright ("Unsupported parameter: 'top_p' is
  // not supported with this model.") and temperature accepts only the provider's own default, so
  // there is nothing to tune and omitting both is the one payload that always works.
  if (provider === PROVIDER_TYPE.OPEN_AI && isReasoningModel(model)) {
    return {};
  }

  if (
    provider === PROVIDER_TYPE.GEMINI ||
    provider === PROVIDER_TYPE.VERTEX_AI
  ) {
    const params = getGeminiOrVertexListedParams(model);
    if (params) {
      return resolveSampling(params, configs);
    }
  }

  if (!supportsGeminiSamplingParams(model)) {
    return {};
  }

  // Claude rejects the pair wherever it is served from, not only under the Anthropic provider —
  // Bedrock answers "temperature and top_p cannot both be specified for this model". Temperature
  // wins, as it does in the Anthropic branch above.
  if (temperature !== undefined && topP !== undefined && isClaudeModel(model)) {
    return { temperature };
  }

  return { temperature, topP };
};

// LlmProviderOpenAiResponsesMapper drops both penalties on a key set to the Responses API, which
// rejects them. The OpenAI list is checked because getProviderFromModel also answers OpenAI for a
// custom gateway's ids, and those never reach the OpenAI key.
const isSentThroughOpenAiResponsesApi = (
  model: PROVIDER_MODEL_TYPE | "",
  openAiPipelineMode?: OpenAiPipelineMode,
): boolean =>
  openAiPipelineMode === "responses_api" &&
  (getLatestProviderModelsSnapshot()[PROVIDER_TYPE.OPEN_AI] ?? []).some(
    (option) => option.value === model,
  );

export const supportsPenaltyParams = (
  model?: PROVIDER_MODEL_TYPE | "",
  openAiPipelineMode?: OpenAiPipelineMode,
): boolean => {
  if (!model) {
    return true;
  }

  const provider = getProviderFromModel(model as PROVIDER_MODEL_TYPE);

  if (provider === PROVIDER_TYPE.OPEN_ROUTER) {
    const native = getNativeModelBehindOpenRouter(model);
    // No pipeline mode: that belongs to the OpenAI key, which never carries an OpenRouter request.
    return !native || supportsPenaltyParams(native);
  }

  if (provider === PROVIDER_TYPE.OPEN_AI) {
    const params = getOpenAIListedParams(model, openAiPipelineMode);
    if (params) {
      return (
        isAccepted(params.frequency_penalty) &&
        isAccepted(params.presence_penalty)
      );
    }
  }

  return (
    provider !== PROVIDER_TYPE.OPEN_AI ||
    (!isReasoningModel(model) &&
      !isSentThroughOpenAiResponsesApi(model, openAiPipelineMode))
  );
};

export type EffortParams = {
  reasoningEffort?: OpenAIReasoningEffort;
  thinkingEffort?: AnthropicThinkingEffort;
  custom_parameters?: unknown;
};

/**
 * The effort a model will actually run at, for the providers that expose one. The companion to
 * {@link resolveSamplingParams} for the effort dropdowns.
 *
 * Unlike the sampling pair this does substitute a default, because the dropdown has no empty state:
 * it renders the model's default for a config holding nothing, which is also what a fresh config is
 * seeded with. Resolving to that same value is what stops the control claiming an effort the request
 * never carries — a model change into a reasoning model leaves the config's effort unset, and the
 * provider would then apply its own default rather than the one the panel showed.
 *
 * Both defaults are the model's own (none on gpt-5.1 to gpt-5.4, medium on Opus 5.5). An Anthropic
 * effort under custom_parameters counts as stored too: the flat one wins when the model offers it,
 * else the nested one does, so a reloaded request shape shows the level it will send and a stale
 * flat value cannot override a valid nested one.
 */
export const resolveEffort = (
  model: PROVIDER_MODEL_TYPE | "",
  configs: EffortParams,
  openAiPipelineMode?: OpenAiPipelineMode,
): EffortParams => {
  if (!model) {
    return { ...configs };
  }

  const provider = getProviderFromModel(model as PROVIDER_MODEL_TYPE);

  if (provider === PROVIDER_TYPE.OPEN_AI) {
    const params = getOpenAIListedParams(model, openAiPipelineMode);
    if (params) {
      const reasoningEffort = resolveOpenAIEffort(
        params,
        configs.reasoningEffort,
      );
      return reasoningEffort ? { reasoningEffort } : {};
    }
    const options = getOpenAIReasoningEffortOptions(model, openAiPipelineMode);
    if (options.length === 0) {
      return {};
    }
    return {
      reasoningEffort: options.some((o) => o.value === configs.reasoningEffort)
        ? configs.reasoningEffort
        : getDefaultReasoningEffort(model),
    };
  }

  if (provider === PROVIDER_TYPE.ANTHROPIC) {
    const options = getAnthropicThinkingEffortOptions(model);
    if (options.length === 0) {
      return {};
    }
    const stored = [
      configs.thinkingEffort,
      getNestedThinkingEffort(configs.custom_parameters),
    ].find((effort) => options.some((o) => o.value === effort));
    return { thinkingEffort: stored ?? getDefaultThinkingEffort(model) };
  }

  // No default substituted: the OpenRouter dropdown has a Default entry that sends no effort, so the
  // model keeps the level OpenRouter picks for it.
  if (provider === PROVIDER_TYPE.OPEN_ROUTER) {
    const options = getOpenRouterReasoningEffortOptions(model);
    return options.some((o) => o.value === configs.reasoningEffort)
      ? { reasoningEffort: configs.reasoningEffort }
      : {};
  }

  return { ...configs };
};

// Used for a model the pricing data does not list: the caps the panels had before it was read.
const MAX_COMPLETION_TOKENS_FALLBACK = {
  [PROVIDER_TYPE.OPEN_AI]: 128000,
  [PROVIDER_TYPE.ANTHROPIC]: 64000,
};

export type MaxCompletionTokensRange = { min: number; max: number };

export const getMaxCompletionTokensRange = (
  provider: keyof typeof MAX_COMPLETION_TOKENS_FALLBACK,
  model: string,
  openAiPipelineMode?: OpenAiPipelineMode,
): MaxCompletionTokensRange => {
  const catalogRange =
    provider === PROVIDER_TYPE.ANTHROPIC
      ? getAnthropicListedParams(model as PROVIDER_MODEL_TYPE)
          ?.max_output_tokens
      : undefined;
  if (catalogRange?.min !== undefined && catalogRange.max !== undefined) {
    return { min: catalogRange.min, max: catalogRange.max };
  }
  return getPricingMaxCompletionTokensRange(
    provider,
    model,
    openAiPipelineMode,
  );
};

const getPricingMaxCompletionTokensRange = (
  provider: keyof typeof MAX_COMPLETION_TOKENS_FALLBACK,
  model: string,
  openAiPipelineMode?: OpenAiPipelineMode,
): MaxCompletionTokensRange => ({
  // Below 16, the Responses API rejects max_output_tokens. Elsewhere 0 is rejected too: OpenAI
  // wants at least 1, and Anthropic refuses 0 on a streamed request, which every playground run is.
  min:
    provider === PROVIDER_TYPE.OPEN_AI && openAiPipelineMode === "responses_api"
      ? 16
      : 1,
  max: getMaxOutputTokens(model) ?? MAX_COMPLETION_TOKENS_FALLBACK[provider],
});

const MAX_COMPLETION_TOKENS_DEFAULT = {
  [PROVIDER_TYPE.OPEN_AI]: DEFAULT_OPEN_AI_CONFIGS.MAX_COMPLETION_TOKENS,
  [PROVIDER_TYPE.ANTHROPIC]: DEFAULT_ANTHROPIC_CONFIGS.MAX_COMPLETION_TOKENS,
};

export const resolveMaxCompletionTokens = (
  provider: keyof typeof MAX_COMPLETION_TOKENS_FALLBACK,
  model: string,
  value: number | undefined,
  openAiPipelineMode?: OpenAiPipelineMode,
): number => {
  const { min, max } = getMaxCompletionTokensRange(
    provider,
    model,
    openAiPipelineMode,
  );
  // A stored 0 was saved while the slider still went down to 0: it meant "not set", never a
  // one-token answer, so it gets the default rather than the new floor.
  const catalogDefault =
    provider === PROVIDER_TYPE.ANTHROPIC
      ? getAnthropicListedParams(model as PROVIDER_MODEL_TYPE)
          ?.max_output_tokens?.default
      : undefined;
  const requested =
    value || (catalogDefault ?? MAX_COMPLETION_TOKENS_DEFAULT[provider]);
  return Math.min(Math.max(Math.floor(requested), min), max);
};

export const hasThinkingInCustomParameters = (configs: EffortParams): boolean =>
  asRecord(configs.custom_parameters).thinking != null;

// Mirrors the backend, which leaves the sampling pair out whenever thinking is on, since Anthropic
// refuses it then: "temperature may only be set to 1 when thinking is enabled". A thinking block in
// custom_parameters decides on its own, "disabled" included, even beside an effort level. Only
// without one does a level on a model that thinks only when asked turn on adaptive thinking.
export const isAnthropicThinkingRequested = (
  model: PROVIDER_MODEL_TYPE | "",
  configs: EffortParams,
): boolean => {
  if (hasThinkingInCustomParameters(configs)) {
    const type = asRecord(asRecord(configs.custom_parameters).thinking).type;
    return (
      typeof type === "string" &&
      type.trim() !== "" &&
      type.toLowerCase() !== "disabled"
    );
  }
  return (
    thinksOnlyWhenAsked(model) &&
    resolveEffort(model, configs).thinkingEffort !== "off"
  );
};

export const getOpenRouterDefaultMaxTokens = (
  model?: PROVIDER_MODEL_TYPE | "",
): number => {
  const modelMaxOutputTokens = getOpenRouterMaxOutputTokens(model);
  return modelMaxOutputTokens
    ? Math.min(DEFAULT_OPEN_ROUTER_CONFIGS.MAX_TOKENS, modelMaxOutputTokens)
    : DEFAULT_OPEN_ROUTER_CONFIGS.MAX_TOKENS;
};

const OPEN_ROUTER_MAX_TOKENS_LIMIT = 10000;

export const getOpenRouterMaxTokensLimit = (
  model?: PROVIDER_MODEL_TYPE | "",
): number => {
  const modelMaxOutputTokens = getOpenRouterMaxOutputTokens(model);
  return modelMaxOutputTokens
    ? Math.min(OPEN_ROUTER_MAX_TOKENS_LIMIT, modelMaxOutputTokens)
    : OPEN_ROUTER_MAX_TOKENS_LIMIT;
};

// Only shown and sent clamped, never written back, so switching back to a model with a higher
// limit brings the stored value back, as resolveMaxCompletionTokens does for OpenAI and Anthropic.
export const resolveOpenRouterMaxTokens = (
  model: PROVIDER_MODEL_TYPE | "" | undefined,
  value: number | undefined,
): number | undefined =>
  value ? Math.min(value, getOpenRouterMaxTokensLimit(model)) : value;

// What both writers share. Anthropic requires max_tokens, so its request always carries one; OpenAI's
// is optional and its panel only shows a stored number, so only a stored one is sent.
const writeCatalogRequest = (
  configs: Record<string, unknown>,
  params: ModelParams,
  sampling: SamplingValues,
  requiredMaxTokens?: number,
): Record<string, unknown> => {
  const sanitized: Record<string, unknown> = omit(configs, [
    "temperature",
    "topP",
    "reasoningEffort",
    "thinkingEffort",
  ]);

  if (sampling.temperature !== undefined) {
    sanitized.temperature = sampling.temperature;
  }
  if (sampling.topP !== undefined) {
    sanitized.topP = sampling.topP;
  }

  const storedMaxTokens =
    typeof configs.maxCompletionTokens === "number"
      ? configs.maxCompletionTokens
      : undefined;
  if (storedMaxTokens !== undefined || requiredMaxTokens !== undefined) {
    const maxCompletionTokens =
      resolveMaxOutputTokens(params.max_output_tokens, storedMaxTokens) ??
      requiredMaxTokens;
    if (maxCompletionTokens === undefined) {
      delete sanitized.maxCompletionTokens;
    } else {
      sanitized.maxCompletionTokens = maxCompletionTokens;
    }
  }

  if (sanitized.thinkingLevel != null) {
    delete sanitized.thinkingLevel;
  }

  return sanitized;
};

// Today's wire shape from the catalog: temperature or top_p (or neither), max_completion_tokens, and the
// effort under custom_parameters.output_config. Sending catalog names and letting the backend write the
// shape is a later step.
export const writeAnthropicRequest = (
  model: PROVIDER_MODEL_TYPE | "",
  configs: Record<string, unknown>,
  params: ModelParams,
): Record<string, unknown> => {
  const sanitized = writeCatalogRequest(
    configs,
    params,
    isAnthropicThinkingRequested(model, configs as EffortParams)
      ? {}
      : resolveSampling(params, configs as SamplingParams),
    DEFAULT_ANTHROPIC_CONFIGS.MAX_COMPLETION_TOKENS,
  );

  const effort = resolveEffort(model, configs as EffortParams).thinkingEffort;
  const customParameters = withThinkingEffort(
    configs.custom_parameters,
    effort,
  );
  if (customParameters) {
    sanitized.custom_parameters = customParameters;
  } else {
    delete sanitized.custom_parameters;
  }

  return sanitized;
};

export const writeOpenAIRequest = (
  configs: Record<string, unknown>,
  params: ModelParams,
): Record<string, unknown> => {
  const sanitized = writeCatalogRequest(
    configs,
    params,
    resolveSampling(params, configs as SamplingParams),
  );

  if (!isAccepted(params.frequency_penalty)) {
    delete sanitized.frequencyPenalty;
  }
  if (!isAccepted(params.presence_penalty)) {
    delete sanitized.presencePenalty;
  }

  const reasoningEffort = resolveOpenAIEffort(params, configs.reasoningEffort);
  if (reasoningEffort) {
    sanitized.reasoningEffort = reasoningEffort;
  }

  return sanitized;
};

// Last-mile request hardening, complementary to updateProviderConfig: this
// layer doesn't trust upstream and keeps the payload valid for stale state
// (e.g. older persisted prompts missing maxCompletionTokens).
export const sanitizeConfigForRequest = (
  model: PROVIDER_MODEL_TYPE | "",
  configs: Record<string, unknown>,
  openAiPipelineMode?: OpenAiPipelineMode,
  promptProvider?: string,
): Record<string, unknown> => {
  if (!model) return configs;

  const provider = getProviderFromModel(model as PROVIDER_MODEL_TYPE);

  if (provider === PROVIDER_TYPE.ANTHROPIC) {
    const params = getAnthropicListedParams(model);
    if (params) {
      return writeAnthropicRequest(model, configs, params);
    }
  }

  if (provider === PROVIDER_TYPE.OPEN_AI) {
    const params = getOpenAIListedParams(model, openAiPipelineMode);
    if (params) {
      return writeOpenAIRequest(configs, params);
    }
  }

  const sanitized: Record<string, unknown> = { ...configs };

  const sampling = resolveSamplingParams(
    model,
    configs as SamplingParams & EffortParams,
  );
  for (const key of ["temperature", "topP"] as const) {
    if (sampling[key] === undefined) {
      delete sanitized[key];
    } else {
      sanitized[key] = sampling[key];
    }
  }

  if (!supportsPenaltyParams(model, openAiPipelineMode)) {
    delete sanitized.frequencyPenalty;
    delete sanitized.presencePenalty;
  }

  if (provider === PROVIDER_TYPE.OPEN_ROUTER) {
    for (const param of Object.keys(
      OPEN_ROUTER_PARAM_NAMES,
    ) as OpenRouterParam[]) {
      if (!supportsOpenRouterParam(model, param)) {
        delete sanitized[param];
      }
    }
  }

  // On these providers the slider starts at 0, and 0 means no limit, so it is left out rather than
  // sent. Rounded down first: the backend truncates a fraction, so a 0.5 would reach them as 0.
  if (
    provider === PROVIDER_TYPE.OPEN_ROUTER ||
    provider === PROVIDER_TYPE.GEMINI ||
    provider === PROVIDER_TYPE.VERTEX_AI ||
    isCustomProviderModel(model)
  ) {
    for (const key of ["maxTokens", "maxCompletionTokens"] as const) {
      const value = sanitized[key];
      if (typeof value !== "number") {
        continue;
      }
      if (Math.floor(value) > 0) {
        sanitized[key] =
          provider === PROVIDER_TYPE.OPEN_ROUTER
            ? resolveOpenRouterMaxTokens(model, Math.floor(value))
            : provider === PROVIDER_TYPE.GEMINI ||
                provider === PROVIDER_TYPE.VERTEX_AI
              ? resolveGeminiMaxOutputTokens(model, Math.floor(value))
              : Math.floor(value);
      } else {
        delete sanitized[key];
      }
    }
  }

  // Prompts stored while the Top K slider stepped by 0.01 can still carry a fraction.
  if (
    provider === PROVIDER_TYPE.OPEN_ROUTER &&
    typeof sanitized.topK === "number"
  ) {
    sanitized.topK = Math.round(sanitized.topK);
  }

  // Same trap as thinking_level below: ChatCompletionRequest has no field for these, so sent flat
  // they are dropped, while custom_parameters entries reach OpenRouter as top-level keys.
  // The effort travels the same way, as OpenRouter's reasoning object: a flat reasoning_effort would
  // reach OpenRouter from the playground, but test-suite runs forward it for OpenAI models only.
  if (provider === PROVIDER_TYPE.OPEN_ROUTER) {
    const nested: Record<string, unknown> = {};
    const dropped: string[] = [];
    for (const [key, wireKey] of Object.entries({
      topK: "top_k",
      minP: "min_p",
      topA: "top_a",
      repetitionPenalty: "repetition_penalty",
    })) {
      if (sanitized[key] != null) {
        nested[wireKey] = sanitized[key];
      } else if (!supportsOpenRouterParam(model, key as OpenRouterParam)) {
        dropped.push(wireKey);
      }
      delete sanitized[key];
    }

    const stored: Record<string, unknown> | undefined = isPlainObject(
      sanitized.custom_parameters,
    )
      ? omit(sanitized.custom_parameters as Record<string, unknown>, dropped)
      : undefined;

    const { reasoningEffort } = resolveEffort(model, configs as EffortParams);
    delete sanitized.reasoningEffort;
    if (getOpenRouterReasoningEffortOptions(model).length > 0) {
      const reasoning = omit(asRecord(stored?.reasoning), "effort");
      if (reasoningEffort) {
        reasoning.effort = reasoningEffort;
      }
      if (Object.keys(reasoning).length > 0) {
        nested.reasoning = reasoning;
      } else {
        delete stored?.reasoning;
      }
    }

    if (stored || Object.keys(nested).length > 0) {
      const customParameters = { ...stored, ...nested };
      if (Object.keys(customParameters).length > 0) {
        sanitized.custom_parameters = customParameters;
      } else {
        delete sanitized.custom_parameters;
      }
    }
  }

  if (
    provider === PROVIDER_TYPE.ANTHROPIC &&
    sanitized.maxCompletionTokens == null
  ) {
    sanitized.maxCompletionTokens =
      DEFAULT_ANTHROPIC_CONFIGS.MAX_COMPLETION_TOKENS;
  }

  // An OpenAI or Anthropic prompt on an id the registry doesn't list shows the provider profile's token
  // range, so its request is held to the same one. Only the prompt's provider can say which profile:
  // getProviderFromModel answers OpenAI for every unlisted id.
  const panelProvider = promptProvider
    ? parseComposedProviderType(promptProvider as COMPOSED_PROVIDER_TYPE)
    : undefined;
  const profile =
    (panelProvider === PROVIDER_TYPE.OPEN_AI ||
      panelProvider === PROVIDER_TYPE.ANTHROPIC) &&
    !getLatestListedModelParams(panelProvider, model)
      ? getModelParams(
          panelProvider,
          model,
          panelProvider === PROVIDER_TYPE.OPEN_AI
            ? toParamsRoute(openAiPipelineMode)
            : undefined,
        )
      : undefined;
  if (
    profile?.max_output_tokens &&
    typeof sanitized.maxCompletionTokens === "number"
  ) {
    sanitized.maxCompletionTokens = resolveMaxOutputTokens(
      profile.max_output_tokens,
      sanitized.maxCompletionTokens,
    );
  }
  // Only ever removes: a value the profile's panel hides is left out (Anthropic's hides all sampling),
  // and nothing the rules above dropped comes back.
  if (profile) {
    const profileSampling = resolveSampling(
      profile,
      sanitized as SamplingParams,
    );
    for (const key of ["temperature", "topP"] as const) {
      if (profileSampling[key] === undefined) {
        delete sanitized[key];
      }
    }
  }

  // getProviderFromModel answers OpenAI for any model the registry does not list, a custom gateway's
  // ids included, so only a listed model is held to OpenAI's or Anthropic's limits.
  if (
    (provider === PROVIDER_TYPE.ANTHROPIC ||
      provider === PROVIDER_TYPE.OPEN_AI) &&
    (getLatestProviderModelsSnapshot()[provider] ?? []).some(
      (option) => option.value === model,
    ) &&
    typeof sanitized.maxCompletionTokens === "number"
  ) {
    sanitized.maxCompletionTokens = resolveMaxCompletionTokens(
      provider,
      model,
      sanitized.maxCompletionTokens,
      openAiPipelineMode,
    );
  }

  if (
    provider === PROVIDER_TYPE.ANTHROPIC ||
    provider === PROVIDER_TYPE.OPEN_AI
  ) {
    const effort = resolveEffort(
      model,
      configs as EffortParams,
      openAiPipelineMode,
    );
    if (effort.reasoningEffort === undefined) {
      delete sanitized.reasoningEffort;
    } else {
      sanitized.reasoningEffort = effort.reasoningEffort;
    }

    // Anthropic reads output_config.effort, and a flat thinking_effort is one more unknown top-level
    // field the backend's ChatCompletionRequest drops, so the effort travels in custom_parameters.
    delete sanitized.thinkingEffort;
    if (provider === PROVIDER_TYPE.ANTHROPIC) {
      const customParameters = withThinkingEffort(
        sanitized.custom_parameters,
        knowsAnthropicEffortLevels(model)
          ? effort.thinkingEffort
          : getNestedThinkingEffort(sanitized.custom_parameters),
      );
      if (customParameters) {
        sanitized.custom_parameters = customParameters;
      } else {
        delete sanitized.custom_parameters;
      }
    }
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
    // request has to send it rather than nothing, unless that default is a level left to the model.
    // A level already nested under custom_parameters counts as stored. Callers that persist the
    // sanitized output and feed it back — the optimizer form reloads a saved run's `parameters`
    // blob wholesale — have no flat thinkingLevel to offer, and substituting the model default
    // there would silently reset the user's saved choice on every re-run.
    // A nested level the model still offers is a real past choice and is honoured, except one left to
    // the model: a saved Minimal on Flash Lite is sent as nothing, the same request by Google's tables.
    const level = resolveThinkingLevel(model, sanitized as ThinkingLevelParams);

    // Dropped unconditionally: the field is Opik's own, and no provider accepts it at the top
    // level, so leaving it on the payload can only be dead weight.
    delete sanitized.thinkingLevel;

    // A level left to the model sends no thinkingConfig: it drops a persisted level and budget, since
    // either would pin how much the model thinks, and keeps the rest of the block, which the form
    // cannot represent.
    const leftToModel = leavesThinkingToModel(model, level);
    if (leftToModel && thinkingLevelOptions.length > 0) {
      const customParameters = withoutThinkingAmount(
        sanitized.custom_parameters,
      );
      if (customParameters) {
        sanitized.custom_parameters = customParameters;
      } else {
        delete sanitized.custom_parameters;
      }
    }

    // `level` is already known to be one this model offers.
    if (!leftToModel && thinkingLevelOptions.length > 0) {
      const customParameters =
        (sanitized.custom_parameters as Record<string, unknown>) ?? {};
      const thinking =
        (customParameters.thinking as Record<string, unknown>) ?? {};

      // Merged into the existing block so include_thoughts survives. A budget_tokens set through the
      // API does not: the backend lets it outrank any level, so the model would get that budget
      // while the panel shows Low, or thinking would stay on under Off.
      sanitized.custom_parameters = {
        ...customParameters,
        thinking: { ...omit(thinking, "budget_tokens"), level },
      };
    }
  }

  return sanitized;
};

const RUNNER_SETTINGS = ["throttling", "maxConcurrentRequests"];

export const getRequestParameters = (
  model: PROVIDER_MODEL_TYPE | "",
  configs: LLMPromptConfigsType,
  openAiPipelineMode?: OpenAiPipelineMode,
  promptProvider?: string,
): Record<string, unknown> =>
  snakeCaseObj(
    omit(
      sanitizeConfigForRequest(
        model,
        configs as unknown as Record<string, unknown>,
        openAiPipelineMode,
        promptProvider,
      ),
      RUNNER_SETTINGS,
    ),
  );
