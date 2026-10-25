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
  OpenRouterParam,
  getGeminiOrVertexListedParams,
  getOpenRouterReasoningEffortOptions,
  isAnthropicThinkingRequested,
  resolveEffort,
  resolveSamplingParams,
  supportsGeminiThinkingLevel,
  supportsOpenRouterParam,
  supportsPenaltyParams,
  supportsSamplingParams,
  supportsVertexAIThinkingLevel,
} from "@/lib/modelUtils";
import { ModelConfigParam } from "@/v2/pages-shared/llm/PromptModelSettings/modelConfigParams";
import { getModelParams, toParamsRoute } from "@/lib/modelParams";
import { ModelParams } from "@/types/modelParams";
import {
  ANTHROPIC_PANEL,
  GEMINI_PANEL,
  GenericPanelPresentation,
  getShownControls,
  OPENAI_PANEL,
  VERTEX_AI_PANEL,
} from "@/v2/pages-shared/llm/PromptModelSettings/providerConfigs/genericPanels";
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
    samplingParams:
      supportsSamplingParams(model) &&
      !isAnthropicThinkingRequested(model ?? "", configs),
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

export const offersOpenRouterTopP = (
  model: PROVIDER_MODEL_TYPE | "" | undefined,
  supports: SupportsParam,
) => supports("topP") && supportsOpenRouterParam(model, "topP");

export const getOpenRouterVisibleControls = ({
  model,
  configs,
  supports,
}: VisibleControlsInput<Partial<LLMOpenRouterConfigsType>>) => {
  const sampling = resolveSamplingPresentation(model);
  const { temperature, topP } = resolveSamplingParams(model ?? "", configs);
  const independent = sampling === "independent";
  const showPenalties = supportsPenaltyParams(model);
  const shows = (param: OpenRouterParam) =>
    supportsOpenRouterParam(model, param) && !isUndefined(configs[param]);

  return {
    // ExclusiveSamplingParams renders nothing when it can offer no choice and neither half is live.
    samplingParams:
      sampling === "exclusive" &&
      (offersOpenRouterTopP(model, supports) ||
        !isUndefined(temperature) ||
        !isUndefined(topP)),
    temperature: independent && !isUndefined(temperature),
    maxTokens: shows("maxTokens"),
    topP: independent && supports("topP") && !isUndefined(topP),
    topK: shows("topK"),
    frequencyPenalty: showPenalties && shows("frequencyPenalty"),
    presencePenalty: showPenalties && shows("presencePenalty"),
    repetitionPenalty: shows("repetitionPenalty"),
    minP: shows("minP"),
    topA: shows("topA"),
    reasoningEffort:
      supports("reasoningEffort") &&
      getOpenRouterReasoningEffortOptions(model).length > 0,
    throttling: supports("throttling"),
    maxConcurrentRequests: supports("maxConcurrentRequests"),
  };
};

export const hasGenericControls = (
  params: ModelParams,
  presentation: GenericPanelPresentation,
  configs: Record<string, unknown>,
  unsupportedParams?: ReadonlySet<ModelConfigParam>,
  model = "",
): boolean => {
  const supports = createSupports(unsupportedParams);
  return (
    getShownControls(params, presentation, configs, unsupportedParams, model)
      .length > 0 ||
    supports("throttling") ||
    supports("maxConcurrentRequests")
  );
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
    case PROVIDER_TYPE.OPEN_AI: {
      const params = getModelParams(
        PROVIDER_TYPE.OPEN_AI,
        model,
        toParamsRoute(openAiPipelineMode),
      );
      if (params) {
        return hasGenericControls(
          params,
          OPENAI_PANEL,
          configs as Record<string, unknown>,
          unsupportedParams,
          model,
        );
      }
      return isAnyControlVisible(
        getOpenAIVisibleControls({
          model,
          configs: configs as Partial<LLMOpenAIConfigsType>,
          supports,
          openAiPipelineMode,
        }),
      );
    }
    case PROVIDER_TYPE.ANTHROPIC: {
      const params = getModelParams(PROVIDER_TYPE.ANTHROPIC, model);
      if (params) {
        return hasGenericControls(
          params,
          ANTHROPIC_PANEL,
          configs as Record<string, unknown>,
          unsupportedParams,
          model,
        );
      }
      return isAnyControlVisible(
        getAnthropicVisibleControls({
          model,
          configs: configs as Partial<LLMAnthropicConfigsType>,
          supports,
        }),
      );
    }
    case PROVIDER_TYPE.GEMINI: {
      const params = getGeminiOrVertexListedParams(model);
      if (params) {
        return hasGenericControls(
          params,
          GEMINI_PANEL,
          configs as Record<string, unknown>,
          unsupportedParams,
          model,
        );
      }
      return isAnyControlVisible(
        getGeminiVisibleControls({
          model,
          configs: configs as Partial<LLMGeminiConfigsType>,
          supports,
        }),
      );
    }
    case PROVIDER_TYPE.VERTEX_AI: {
      const params = getGeminiOrVertexListedParams(model);
      if (params) {
        return hasGenericControls(
          params,
          VERTEX_AI_PANEL,
          configs as Record<string, unknown>,
          unsupportedParams,
          model,
        );
      }
      return isAnyControlVisible(
        getVertexAIVisibleControls({
          model,
          configs: configs as Partial<LLMVertexAIConfigsType>,
          supports,
        }),
      );
    }
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
