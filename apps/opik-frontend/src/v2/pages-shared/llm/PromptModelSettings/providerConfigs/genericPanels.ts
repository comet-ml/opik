import {
  DEFAULT_ANTHROPIC_CONFIGS,
  DEFAULT_OPEN_AI_CONFIGS,
} from "@/constants/llm";
import {
  EffortParams,
  getAnthropicThinkingEffortLabel,
  getAnthropicThinkingEffortOptions,
  getOpenAIEffortLevels,
  getOpenAIReasoningEffortLabel,
  hasEffortWithoutThinking,
  hasThinkingInCustomParameters,
  isAnthropicThinkingRequested,
  resolveEffort,
  resolveOpenAIConfigEffort,
} from "@/lib/modelUtils";
import {
  ANTHROPIC_PARAM_BINDING,
  getParamControls,
  OPENAI_PARAM_BINDING,
  ParamBinding,
  ParamControl,
  refusesSamplingAt,
  resolveMaxOutputTokens,
  resolveSampling,
} from "@/lib/modelParams";
import { ModelParams } from "@/types/modelParams";
import { PROVIDER_MODEL_TYPE } from "@/types/providers";
import {
  getAnthropicEffortDescription,
  getSamplingOffNote,
} from "@/v2/pages-shared/llm/PromptModelSettings/providerConfigs/anthropicPanelText";

export type EffortChoice = { levels: string[]; value: string | undefined };

export type GenericPanelPresentation = {
  binding: ParamBinding;
  // The OpenAI panel offers one slider per number its config holds, so a surface whose config omits a
  // number it cannot store (a judge rule keeps no penalties) gets no slider for it.
  storedNumbersOnly: boolean;
  reasoningNoun: string;
  getEffortTitle: (model: string) => string;
  getEffortDescription: (model: string) => string;
  effortPlaceholder: string;
  getEffortLabel: (level: string) => string;
  // The levels and the value come from the same helpers the request reads, so what the panel shows is
  // what gets sent: for a Claude model that thinks only when asked, that adds Off and starts there.
  getEffortChoice: (
    model: string,
    params: ModelParams,
    configs: Record<string, unknown>,
  ) => EffortChoice;
  getSamplingOffNote: (
    model: string,
    configs: Record<string, unknown>,
    withTopP: boolean,
  ) => string | undefined;
  throttlingDefault: number;
  maxConcurrentRequestsDefault: number;
};

export const ANTHROPIC_PANEL: GenericPanelPresentation = {
  binding: ANTHROPIC_PARAM_BINDING,
  storedNumbersOnly: false,
  reasoningNoun: "thinking",
  getEffortTitle: (model) =>
    hasEffortWithoutThinking(model as PROVIDER_MODEL_TYPE)
      ? "Effort"
      : "Thinking effort",
  getEffortDescription: (model) =>
    getAnthropicEffortDescription(model as PROVIDER_MODEL_TYPE),
  effortPlaceholder: "Select thinking effort",
  getEffortLabel: getAnthropicThinkingEffortLabel,
  getEffortChoice: (model, _params, configs) => ({
    levels: getAnthropicThinkingEffortOptions(model as PROVIDER_MODEL_TYPE).map(
      (option) => option.value,
    ),
    value: resolveEffort(model as PROVIDER_MODEL_TYPE, configs as EffortParams)
      .thinkingEffort,
  }),
  getSamplingOffNote: (model, configs, withTopP) =>
    isAnthropicThinkingRequested(
      model as PROVIDER_MODEL_TYPE,
      configs as EffortParams,
    )
      ? getSamplingOffNote(
          withTopP,
          hasThinkingInCustomParameters(configs as EffortParams),
        )
      : undefined,
  throttlingDefault: DEFAULT_ANTHROPIC_CONFIGS.THROTTLING,
  maxConcurrentRequestsDefault:
    DEFAULT_ANTHROPIC_CONFIGS.MAX_CONCURRENT_REQUESTS,
};

export const OPENAI_PANEL: GenericPanelPresentation = {
  binding: OPENAI_PARAM_BINDING,
  storedNumbersOnly: true,
  reasoningNoun: "reasoning",
  getEffortTitle: () => "Reasoning effort",
  getEffortDescription: () =>
    "Controls how much effort the model puts into reasoning before responding. Higher effort may result in more thoughtful but slower responses.",
  effortPlaceholder: "Select reasoning effort",
  getEffortLabel: getOpenAIReasoningEffortLabel,
  getEffortChoice: (_model, params, configs) => ({
    levels: getOpenAIEffortLevels(params),
    value: resolveOpenAIConfigEffort(params, configs as EffortParams),
  }),
  getSamplingOffNote: () => undefined,
  throttlingDefault: DEFAULT_OPEN_AI_CONFIGS.THROTTLING,
  maxConcurrentRequestsDefault: DEFAULT_OPEN_AI_CONFIGS.MAX_CONCURRENT_REQUESTS,
};

const keepsStoredNumber = (
  control: ParamControl,
  configs: Record<string, unknown>,
): boolean => control.kind !== "number" || configs[control.configKey] != null;

const isSamplingControl = (control: ParamControl): boolean =>
  control.kind === "sampling_choice" ||
  (control.kind === "number" &&
    (control.param === "temperature" || control.param === "top_p"));

export const getShownControls = (
  params: ModelParams,
  presentation: GenericPanelPresentation,
  configs: Record<string, unknown>,
  unsupportedParams?: ReadonlySet<string>,
  model = "",
): ParamControl[] => {
  const { levels, value } = presentation.getEffortChoice(
    model,
    params,
    configs,
  );
  const samplingOff =
    presentation.getSamplingOffNote(model, configs, false) !== undefined ||
    refusesSamplingAt(params, value);
  const controls = getParamControls(
    params,
    presentation.binding,
    unsupportedParams,
  ).flatMap((control): ParamControl[] => {
    if (control.kind === "effort") {
      return levels.length > 0 && value !== undefined
        ? [{ ...control, levels }]
        : [];
    }
    return samplingOff && isSamplingControl(control) ? [] : [control];
  });
  return presentation.storedNumbersOnly
    ? controls.filter((control) => keepsStoredNumber(control, configs))
    : controls;
};

export const getShownValue = (
  control: ParamControl,
  params: ModelParams,
  presentation: GenericPanelPresentation,
  configs: Record<string, unknown>,
  model = "",
): unknown => {
  if (control.kind === "effort") {
    return presentation.getEffortChoice(model, params, configs).value;
  }
  const sampling = resolveSampling(
    params,
    configs,
    presentation.getEffortChoice(model, params, configs).value,
  );
  if (control.kind === "sampling_choice") {
    return { temperature: sampling.temperature, topP: sampling.topP };
  }
  if (control.param === "max_output_tokens") {
    return resolveMaxOutputTokens(
      params.max_output_tokens,
      configs[control.configKey] as number | undefined,
    );
  }
  if (control.param === "temperature") return sampling.temperature;
  if (control.param === "top_p") return sampling.topP;
  return configs[control.configKey];
};
