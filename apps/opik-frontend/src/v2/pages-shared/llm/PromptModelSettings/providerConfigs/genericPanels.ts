import {
  DEFAULT_ANTHROPIC_CONFIGS,
  DEFAULT_GEMINI_CONFIGS,
  DEFAULT_OPEN_AI_CONFIGS,
  DEFAULT_VERTEX_AI_CONFIGS,
} from "@/constants/llm";
import {
  EffortParams,
  getAnthropicThinkingEffortLabel,
  getAnthropicThinkingEffortOptions,
  getOpenAIEffortLevels,
  getOpenAIReasoningEffortLabel,
  getThinkingLevelDescription,
  getThinkingLevelLabel,
  getThinkingLevelOptions,
  hasEffortWithoutThinking,
  hasThinkingInCustomParameters,
  isAnthropicThinkingRequested,
  resolveEffort,
  resolveGeminiMaxOutputTokens,
  resolveOpenAIEffort,
  resolveThinkingLevel,
  ThinkingLevelParams,
} from "@/lib/modelUtils";
import {
  ANTHROPIC_PARAM_BINDING,
  GEMINI_PARAM_BINDING,
  getParamControls,
  OPENAI_PARAM_BINDING,
  ParamBinding,
  ParamControl,
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
  // Gemini's slider starts at 0, which sends no limit, so a stored 0 is shown as it is rather than as
  // the catalog's default the other panels fall back to.
  zeroMeansNoLimit?: boolean;
  // The old Gemini and Vertex AI panels mounted this slider as maxOutputTokens, and the Playwright specs
  // still find it by that id.
  maxOutputTokensId?: string;
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
    value: resolveOpenAIEffort(params, configs.reasoningEffort),
  }),
  getSamplingOffNote: () => undefined,
  throttlingDefault: DEFAULT_OPEN_AI_CONFIGS.THROTTLING,
  maxConcurrentRequestsDefault: DEFAULT_OPEN_AI_CONFIGS.MAX_CONCURRENT_REQUESTS,
};

const geminiFamilyPanel = (
  throttlingDefault: number,
  maxConcurrentRequestsDefault: number,
): GenericPanelPresentation => ({
  binding: GEMINI_PARAM_BINDING,
  storedNumbersOnly: true,
  zeroMeansNoLimit: true,
  maxOutputTokensId: "maxOutputTokens",
  reasoningNoun: "thinking",
  getEffortTitle: () => "Thinking level",
  getEffortDescription: (model) =>
    getThinkingLevelDescription(model as PROVIDER_MODEL_TYPE),
  effortPlaceholder: "Select thinking level",
  getEffortLabel: getThinkingLevelLabel,
  getEffortChoice: (model, _params, configs) => {
    const levels = getThinkingLevelOptions(model as PROVIDER_MODEL_TYPE).map(
      (option) => option.value,
    );
    return {
      levels,
      value:
        levels.length > 0
          ? resolveThinkingLevel(
              model as PROVIDER_MODEL_TYPE,
              configs as ThinkingLevelParams,
            )
          : undefined,
    };
  },
  getSamplingOffNote: () => undefined,
  throttlingDefault,
  maxConcurrentRequestsDefault,
});

export const GEMINI_PANEL = geminiFamilyPanel(
  DEFAULT_GEMINI_CONFIGS.THROTTLING,
  DEFAULT_GEMINI_CONFIGS.MAX_CONCURRENT_REQUESTS,
);

export const VERTEX_AI_PANEL = geminiFamilyPanel(
  DEFAULT_VERTEX_AI_CONFIGS.THROTTLING,
  DEFAULT_VERTEX_AI_CONFIGS.MAX_CONCURRENT_REQUESTS,
);

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
  const samplingOff =
    presentation.getSamplingOffNote(model, configs, false) !== undefined;
  const controls = getParamControls(
    params,
    presentation.binding,
    unsupportedParams,
  ).flatMap((control): ParamControl[] => {
    if (control.kind === "effort") {
      const { levels, value } = presentation.getEffortChoice(
        model,
        params,
        configs,
      );
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
  const sampling = resolveSampling(params, configs);
  if (control.kind === "sampling_choice") {
    return { temperature: sampling.temperature, topP: sampling.topP };
  }
  if (control.param === "max_output_tokens" && presentation.zeroMeansNoLimit) {
    const stored = configs[control.configKey];
    return typeof stored === "number" && Math.floor(stored) > 0
      ? resolveGeminiMaxOutputTokens(
          model as PROVIDER_MODEL_TYPE,
          Math.floor(stored),
        )
      : stored;
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
