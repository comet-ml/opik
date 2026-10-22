import {
  DEFAULT_ANTHROPIC_CONFIGS,
  DEFAULT_OPEN_AI_CONFIGS,
} from "@/constants/llm";
import {
  getAnthropicThinkingEffortLabel,
  getNestedThinkingEffort,
  getOpenAIReasoningEffortLabel,
} from "@/lib/modelUtils";
import {
  ANTHROPIC_PARAM_BINDING,
  getParamControls,
  OPENAI_PARAM_BINDING,
  ParamBinding,
  ParamControl,
} from "@/lib/modelParams";
import { ModelParams } from "@/types/modelParams";

export type GenericPanelPresentation = {
  binding: ParamBinding;
  // The OpenAI panel offers one slider per number its config holds, so a surface whose config omits a
  // number it cannot store (a judge rule keeps no penalties) gets no slider for it.
  storedNumbersOnly: boolean;
  reasoningNoun: string;
  effortLabel: string;
  effortDescription: string;
  effortPlaceholder: string;
  getEffortLabel: (level: string) => string;
  getStoredEffort: (configs: Record<string, unknown>) => unknown[];
  throttlingDefault: number;
  maxConcurrentRequestsDefault: number;
};

export const ANTHROPIC_PANEL: GenericPanelPresentation = {
  binding: ANTHROPIC_PARAM_BINDING,
  storedNumbersOnly: false,
  reasoningNoun: "thinking",
  effortLabel: "Thinking effort",
  effortDescription:
    "Controls how many tokens Claude spends on the whole response, thinking included. Lower effort is faster and cheaper; higher effort is more thorough.",
  effortPlaceholder: "Select thinking effort",
  getEffortLabel: getAnthropicThinkingEffortLabel,
  getStoredEffort: (configs) => [
    configs.thinkingEffort,
    getNestedThinkingEffort(configs.custom_parameters),
  ],
  throttlingDefault: DEFAULT_ANTHROPIC_CONFIGS.THROTTLING,
  maxConcurrentRequestsDefault:
    DEFAULT_ANTHROPIC_CONFIGS.MAX_CONCURRENT_REQUESTS,
};

export const OPENAI_PANEL: GenericPanelPresentation = {
  binding: OPENAI_PARAM_BINDING,
  storedNumbersOnly: true,
  reasoningNoun: "reasoning",
  effortLabel: "Reasoning effort",
  effortDescription:
    "Controls how much effort the model puts into reasoning before responding. Higher effort may result in more thoughtful but slower responses.",
  effortPlaceholder: "Select reasoning effort",
  getEffortLabel: getOpenAIReasoningEffortLabel,
  getStoredEffort: (configs) => [configs.reasoningEffort],
  throttlingDefault: DEFAULT_OPEN_AI_CONFIGS.THROTTLING,
  maxConcurrentRequestsDefault: DEFAULT_OPEN_AI_CONFIGS.MAX_CONCURRENT_REQUESTS,
};

const holdsNumber = (
  control: ParamControl,
  configs: Record<string, unknown>,
): boolean => control.kind !== "number" || configs[control.configKey] != null;

export const getShownControls = (
  params: ModelParams,
  presentation: GenericPanelPresentation,
  configs: Record<string, unknown>,
  unsupportedParams?: ReadonlySet<string>,
): ParamControl[] => {
  const controls = getParamControls(
    params,
    presentation.binding,
    unsupportedParams,
  );
  return presentation.storedNumbersOnly
    ? controls.filter((control) => holdsNumber(control, configs))
    : controls;
};
