import sortBy from "lodash/sortBy";
import {
  getLatestListedModelParams,
  getLatestProviderProfile,
  hasLatestModelList,
} from "@/lib/modelRegistryStore";
import { EffortParam, ModelParams, NumberParam } from "@/types/modelParams";
import { OpenAiPipelineMode } from "@/types/providers";

export const RESPONSES_API_ROUTE = "responses_api";

export const toParamsRoute = (
  openAiPipelineMode?: OpenAiPipelineMode,
): string | undefined =>
  openAiPipelineMode === "responses_api" ? RESPONSES_API_ROUTE : undefined;

export const getModelParams = (
  provider: string,
  model?: string | null,
  route?: string,
): ModelParams | undefined => {
  // Without the model list every model would read as unlisted and get the profile meant for new ids.
  if (!hasLatestModelList(provider)) {
    return undefined;
  }
  const profile = getLatestProviderProfile(provider);
  const params = getLatestListedModelParams(provider, model) ?? profile;
  const routed = (route ? params?.routes?.[route] : undefined) ?? params;
  return (
    routed &&
    withSamplingRanges(
      routed,
      (route ? profile?.routes?.[route] : undefined) ?? profile,
    )
  );
};

// The backend serves a refused param without its range, but sampling that comes back at effort none
// still needs one for the slider shown then, so it takes the provider profile's.
const withSamplingRanges = (
  params: ModelParams,
  profile?: ModelParams,
): ModelParams =>
  params.sampling_at_effort_none && profile
    ? {
        ...params,
        temperature: params.temperature && {
          ...profile.temperature,
          ...params.temperature,
        },
        top_p: params.top_p && { ...profile.top_p, ...params.top_p },
      }
    : params;

export const isAccepted = (param?: { accepted?: boolean }): boolean =>
  param?.accepted === true;

// OpenAI: "When reasoning effort is not none, remove temperature, top_p, and top_logprobs". So a model
// whose catalog says so takes both back while it runs at none, as ParamResolver does. The effort is the
// one the request sends, which is only ever a level the model offers.
const takesSampling = (
  param: NumberParam | undefined,
  params: ModelParams,
  effort: string | undefined,
): boolean =>
  isAccepted(param) ||
  (params.sampling_at_effort_none === true && effort === "none");

export const refusesSamplingAt = (
  params: ModelParams,
  effort: string | undefined,
): boolean =>
  params.sampling_at_effort_none === true &&
  !takesSampling(params.temperature, params, effort);

export const getEffortLevels = (effort?: EffortParam): string[] =>
  isAccepted(effort) ? effort?.levels ?? [] : [];

const getDefaultEffortLevel = (
  effort: EffortParam | undefined,
  levels: readonly string[],
): string | undefined =>
  effort?.default && levels.includes(effort.default)
    ? effort.default
    : levels[0];

// From least to most thinking. "none" and "off" switch thinking off rather than
// being its smallest amount, so a level that thinks never lands on them:
// Minimal on a model offering None and Low becomes Low. "auto" has no rank, it
// leaves the amount to the model.
const THINKING_AMOUNT_RANK: ReadonlyMap<string, number> = new Map([
  ["none", 0],
  ["off", 0],
  ["minimal", 1],
  ["low", 2],
  ["medium", 3],
  ["high", 4],
  ["xhigh", 5],
  ["max", 6],
]);

// Where a level picked on the previous model lands on a model that does not
// offer it: the nearest offered level, a tie going to the lower, cheaper one. A
// level the previous model did not offer either was never on screen (a Max on a
// Chat Completions key shows as the model's default), so it gets none and falls
// to the default.
export const getNearestOfferedLevel = <L extends string>(
  level: L | undefined,
  options: Array<{ value: L }>,
  previousOptions: Array<{ value: L }>,
): L | undefined => {
  const rank =
    level === undefined ? undefined : THINKING_AMOUNT_RANK.get(level);
  if (rank === undefined || !previousOptions.some((o) => o.value === level)) {
    return undefined;
  }
  const ranked = options.flatMap(({ value }) => {
    const valueRank = THINKING_AMOUNT_RANK.get(value);
    return valueRank === undefined || (valueRank === 0 && rank > 0)
      ? []
      : [{ value, valueRank }];
  });
  return sortBy(ranked, [
    ({ valueRank }) => Math.abs(valueRank - rank),
    ({ valueRank }) => valueRank,
  ])[0]?.value;
};

export const resolveEffortLevel = (
  effort: EffortParam | undefined,
  stored: readonly unknown[],
  levels: readonly string[] = getEffortLevels(effort),
): string | undefined => {
  if (levels.length === 0) {
    return undefined;
  }
  const kept = stored.find(
    (value): value is string =>
      typeof value === "string" && levels.includes(value),
  );
  return kept ?? getDefaultEffortLevel(effort, levels);
};

export const clampToParam = (param: NumberParam, value: number): number =>
  Math.min(Math.max(value, param.min ?? -Infinity), param.max ?? Infinity);

export const resolveMaxOutputTokens = (
  param: NumberParam | undefined,
  value: number | null | undefined,
): number | undefined => {
  if (!param || !isAccepted(param)) {
    return undefined;
  }
  // A stored 0 meant "not set" while the slider still went down to 0, never a one-token answer.
  const requested = value || param.default;
  return requested === undefined
    ? undefined
    : clampToParam(param, Math.floor(requested));
};

export type SamplingValues = { temperature?: number; topP?: number };

export const resolveSampling = (
  params: ModelParams,
  configs: { temperature?: number | null; topP?: number | null },
  effort?: string,
): SamplingValues => {
  const takesTemperature = takesSampling(params.temperature, params, effort);
  const temperature = takesTemperature
    ? configs.temperature ?? undefined
    : undefined;
  const topP = takesSampling(params.top_p, params, effort)
    ? configs.topP ?? undefined
    : undefined;

  if (!params.sampling_exclusive) {
    return {
      ...(temperature !== undefined && { temperature }),
      ...(topP !== undefined && { topP }),
    };
  }
  if (temperature !== undefined) {
    return { temperature };
  }
  if (topP !== undefined) {
    return { topP };
  }
  // With neither set, temperature takes over so the panel never shows two live sliders.
  return takesTemperature && params.temperature?.default !== undefined
    ? { temperature: params.temperature.default }
    : {};
};

export type NumberParamName =
  | "temperature"
  | "top_p"
  | "max_output_tokens"
  | "frequency_penalty"
  | "presence_penalty";

export type ParamBinding = Partial<Record<NumberParamName | "effort", string>>;

export const ANTHROPIC_PARAM_BINDING: ParamBinding = {
  temperature: "temperature",
  top_p: "topP",
  max_output_tokens: "maxCompletionTokens",
  effort: "thinkingEffort",
};

export const OPENAI_PARAM_BINDING: ParamBinding = {
  temperature: "temperature",
  top_p: "topP",
  max_output_tokens: "maxCompletionTokens",
  frequency_penalty: "frequencyPenalty",
  presence_penalty: "presencePenalty",
  effort: "reasoningEffort",
};

export type NumberControl = {
  kind: "number";
  param: NumberParamName;
  configKey: string;
  min: number;
  max: number;
  default: number;
};

export type SamplingChoiceControl = {
  kind: "sampling_choice";
  temperature: NumberControl;
  topP?: NumberControl;
};

export type EffortControl = {
  kind: "effort";
  configKey: string;
  levels: string[];
  default: string;
};

export type ParamControl =
  | NumberControl
  | SamplingChoiceControl
  | EffortControl;

export const getParamControls = (
  params: ModelParams,
  binding: ParamBinding,
  unsupported?: ReadonlySet<string>,
): ParamControl[] => {
  const shownKey = (param: keyof ParamBinding): string | undefined => {
    const key = binding[param];
    return key !== undefined && !unsupported?.has(key) ? key : undefined;
  };

  const numberControl = (param: NumberParamName): NumberControl | undefined => {
    const configKey = shownKey(param);
    const spec = params[param];
    const backAtEffortNone =
      params.sampling_at_effort_none === true &&
      (param === "temperature" || param === "top_p");
    if (
      !configKey ||
      !spec ||
      !(isAccepted(spec) || backAtEffortNone) ||
      spec.min === undefined ||
      spec.max === undefined
    ) {
      return undefined;
    }
    return {
      kind: "number",
      param,
      configKey,
      min: spec.min,
      max: spec.max,
      default: spec.default ?? spec.min,
    };
  };

  const controls: ParamControl[] = [];
  const temperature = numberControl("temperature");
  const topP = numberControl("top_p");
  if (params.sampling_exclusive && temperature) {
    controls.push({ kind: "sampling_choice", temperature, topP });
  } else {
    if (temperature) controls.push(temperature);
    if (topP) controls.push(topP);
  }

  for (const param of [
    "max_output_tokens",
    "frequency_penalty",
    "presence_penalty",
  ] as const) {
    const control = numberControl(param);
    if (control) controls.push(control);
  }

  const effortKey = shownKey("effort");
  const levels = getEffortLevels(params.effort);
  const defaultLevel = getDefaultEffortLevel(params.effort, levels);
  if (effortKey && defaultLevel !== undefined) {
    controls.push({
      kind: "effort",
      configKey: effortKey,
      levels,
      default: defaultLevel,
    });
  }

  return controls;
};

/**
 * What a model switch writes back. Numbers are left alone on purpose: they are clamped where they are
 * shown and sent, so switching back to a model with a wider range brings the stored value back.
 * getLevels is for a provider whose request can only send some of the catalog's levels, so a switch
 * never settles on one the panel hides and the request drops.
 */
export const adjustConfigForModel = <T extends object>(
  config: T,
  params: ModelParams,
  binding: ParamBinding,
  previousParams?: ModelParams,
  getLevels: (params?: ModelParams) => readonly string[] = (p) =>
    getEffortLevels(p?.effort),
): T => {
  const effortKey = binding.effort;
  if (!effortKey) {
    return config;
  }
  const current = (config as Record<string, unknown>)[effortKey];
  if (current === undefined) {
    return config;
  }
  const levels = getLevels(params);
  const previousLevels = previousParams ? getLevels(previousParams) : [];
  // A value that is just the previous model's default was never picked, so it follows the next model's default.
  const keptDefault =
    previousLevels.length > 0 &&
    current === getDefaultEffortLevel(previousParams?.effort, previousLevels);
  let next: string | undefined;
  if (levels.length > 0) {
    next = keptDefault
      ? getDefaultEffortLevel(params.effort, levels)
      : typeof current === "string" && levels.includes(current)
        ? current
        : (typeof current === "string"
            ? getNearestOfferedLevel(
                current,
                levels.map((value) => ({ value })),
                previousLevels.map((value) => ({ value })),
              )
            : undefined) ?? getDefaultEffortLevel(params.effort, levels);
  }
  return next === current ? config : { ...config, [effortKey]: next };
};
