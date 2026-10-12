import isUndefined from "lodash/isUndefined";

import {
  LLMAnthropicConfigsType,
  LLMGeminiConfigsType,
  LLMOpenAIConfigsType,
  LLMOpenRouterConfigsType,
  LLMPromptConfigsType,
  LLMVertexAIConfigsType,
  OpenAiPipelineMode,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";
import {
  resolveEffort,
  resolveSamplingParams,
  supportsGeminiThinkingLevel,
  supportsPenaltyParams,
  supportsSamplingParams,
  supportsVertexAIThinkingLevel,
} from "@/lib/modelUtils";
import { ModelConfigParam } from "@/v2/pages-shared/llm/PromptModelSettings/modelConfigParams";
import { resolveSamplingPresentation } from "@/v2/pages-shared/llm/PromptModelSettings/providerConfigs/ExclusiveSamplingParams";

export type SupportsParam = (param: ModelConfigParam) => boolean;

interface VisibleControlsInput<C> {
  model?: PROVIDER_MODEL_TYPE | "";
  configs: C;
  supports: SupportsParam;
}

export const createSupports =
  (unsupportedParams?: ReadonlySet<ModelConfigParam>): SupportsParam =>
  (param) =>
    !unsupportedParams?.has(param);

export const isAnyControlVisible = (controls: Record<string, boolean>) =>
  Object.values(controls).some(Boolean);

export const getOpenAIVisibleControls = ({
  model,
  configs,
  supports,
  openAiPipelineMode,
}: VisibleControlsInput<Partial<LLMOpenAIConfigsType>> & {
  openAiPipelineMode?: OpenAiPipelineMode;
}) => {
  // The resolver owns which sampling params this model accepts and what the request will carry, so
  // both sliders follow it rather than the config's own keys. Reasoning models tune neither.
  const { temperature, topP } = resolveSamplingParams(model ?? "", configs);
  const { reasoningEffort } = resolveEffort(
    model ?? "",
    configs,
    openAiPipelineMode,
  );
  const showPenalties = supportsPenaltyParams(model, openAiPipelineMode);

  return {
    temperature: !isUndefined(temperature),
    maxCompletionTokens:
      supports("maxCompletionTokens") &&
      !isUndefined(configs.maxCompletionTokens),
    topP: supports("topP") && !isUndefined(topP),
    frequencyPenalty: showPenalties && !isUndefined(configs.frequencyPenalty),
    presencePenalty: showPenalties && !isUndefined(configs.presencePenalty),
    reasoningEffort:
      supports("reasoningEffort") && reasoningEffort !== undefined,
    throttling: supports("throttling"),
    maxConcurrentRequests: supports("maxConcurrentRequests"),
  };
};

export const getAnthropicVisibleControls = ({
  model,
  configs,
  supports,
}: VisibleControlsInput<Partial<LLMAnthropicConfigsType>>) => {
  const { thinkingEffort } = resolveEffort(model ?? "", configs);

  return {
    samplingParams: supportsSamplingParams(model),
    maxCompletionTokens: supports("maxCompletionTokens"),
    throttling: supports("throttling"),
    maxConcurrentRequests: supports("maxConcurrentRequests"),
    thinkingEffort: supports("thinkingEffort") && thinkingEffort !== undefined,
  };
};

const getGeminiFamilyVisibleControls = (
  {
    model,
    configs,
    supports,
  }: VisibleControlsInput<
    Partial<LLMGeminiConfigsType> | Partial<LLMVertexAIConfigsType>
  >,
  showThinkingLevel: boolean,
) => {
  const { temperature, topP } = resolveSamplingParams(model ?? "", configs);

  return {
    temperature: !isUndefined(temperature),
    maxCompletionTokens:
      supports("maxCompletionTokens") &&
      !isUndefined(configs.maxCompletionTokens),
    topP: supports("topP") && !isUndefined(topP),
    thinkingLevel: showThinkingLevel,
    throttling: supports("throttling"),
    maxConcurrentRequests: supports("maxConcurrentRequests"),
  };
};

export const getGeminiVisibleControls = (
  input: VisibleControlsInput<Partial<LLMGeminiConfigsType>>,
) =>
  getGeminiFamilyVisibleControls(
    input,
    supportsGeminiThinkingLevel(input.model),
  );

export const getVertexAIVisibleControls = (
  input: VisibleControlsInput<Partial<LLMVertexAIConfigsType>>,
) =>
  getGeminiFamilyVisibleControls(
    input,
    supportsVertexAIThinkingLevel(input.model),
  );

export const getOpenRouterVisibleControls = ({
  model,
  configs,
  supports,
}: VisibleControlsInput<Partial<LLMOpenRouterConfigsType>>) => {
  const sampling = resolveSamplingPresentation(model);
  const { temperature, topP } = resolveSamplingParams(model ?? "", configs);
  const independent = sampling === "independent";
  const showPenalties = supportsPenaltyParams(model);

  return {
    // ExclusiveSamplingParams renders nothing when it can offer no choice and neither half is live.
    samplingParams:
      sampling === "exclusive" &&
      (supports("topP") || !isUndefined(temperature) || !isUndefined(topP)),
    temperature: independent && !isUndefined(temperature),
    maxTokens: !isUndefined(configs.maxTokens),
    topP: independent && supports("topP") && !isUndefined(topP),
    topK: !isUndefined(configs.topK),
    frequencyPenalty: showPenalties && !isUndefined(configs.frequencyPenalty),
    presencePenalty: showPenalties && !isUndefined(configs.presencePenalty),
    repetitionPenalty: !isUndefined(configs.repetitionPenalty),
    minP: !isUndefined(configs.minP),
    topA: !isUndefined(configs.topA),
    throttling: supports("throttling"),
    maxConcurrentRequests: supports("maxConcurrentRequests"),
  };
};

export const hasVisibleControls = (
  provider: PROVIDER_TYPE,
  model: PROVIDER_MODEL_TYPE | "",
  configs: Partial<LLMPromptConfigsType>,
  unsupportedParams?: ReadonlySet<ModelConfigParam>,
  openAiPipelineMode?: OpenAiPipelineMode,
): boolean => {
  const supports = createSupports(unsupportedParams);

  switch (provider) {
    case PROVIDER_TYPE.OPEN_AI:
      return isAnyControlVisible(
        getOpenAIVisibleControls({
          model,
          configs: configs as Partial<LLMOpenAIConfigsType>,
          supports,
          openAiPipelineMode,
        }),
      );
    case PROVIDER_TYPE.ANTHROPIC:
      return isAnyControlVisible(
        getAnthropicVisibleControls({
          model,
          configs: configs as Partial<LLMAnthropicConfigsType>,
          supports,
        }),
      );
    case PROVIDER_TYPE.GEMINI:
      return isAnyControlVisible(
        getGeminiVisibleControls({
          model,
          configs: configs as Partial<LLMGeminiConfigsType>,
          supports,
        }),
      );
    case PROVIDER_TYPE.VERTEX_AI:
      return isAnyControlVisible(
        getVertexAIVisibleControls({
          model,
          configs: configs as Partial<LLMVertexAIConfigsType>,
          supports,
        }),
      );
    case PROVIDER_TYPE.OPEN_ROUTER:
      return isAnyControlVisible(
        getOpenRouterVisibleControls({
          model,
          configs: configs as Partial<LLMOpenRouterConfigsType>,
          supports,
        }),
      );
    case PROVIDER_TYPE.CUSTOM:
    case PROVIDER_TYPE.OLLAMA:
    case PROVIDER_TYPE.BEDROCK:
      return true;
    default:
      return false;
  }
};
