import { PlaygroundPromptType } from "@/types/playground";
import { generateRandomString } from "@/lib/utils";
import {
  DEFAULT_ANTHROPIC_CONFIGS,
  DEFAULT_GEMINI_CONFIGS,
  DEFAULT_OPEN_AI_CONFIGS,
  DEFAULT_OPEN_ROUTER_CONFIGS,
  DEFAULT_VERTEX_AI_CONFIGS,
  DEFAULT_CUSTOM_CONFIGS,
} from "@/constants/llm";
import { getDefaultTemperatureForModel } from "@/lib/modelUtils";
import {
  LLMAnthropicConfigsType,
  LLMGeminiConfigsType,
  LLMOpenAIConfigsType,
  LLMOpenRouterConfigsType,
  LLMPromptConfigsType,
  LLMVertexAIConfigsType,
  LLMCustomConfigsType,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";
import { generateDefaultLLMPromptMessage } from "@/lib/llm";
import {
  ModelResolver,
  ProviderResolver,
} from "@/hooks/useLLMProviderModelsData";
import { RunStreamingReturn } from "@/api/playground/useCompletionProxyStreaming";

export const getDefaultConfigByProvider = (
  provider?: PROVIDER_TYPE | "",
  model?: PROVIDER_MODEL_TYPE | "",
): LLMPromptConfigsType => {
  if (provider === PROVIDER_TYPE.OPEN_AI) {
    return {
      temperature: getDefaultTemperatureForModel(model),
      maxCompletionTokens: DEFAULT_OPEN_AI_CONFIGS.MAX_COMPLETION_TOKENS,
      topP: DEFAULT_OPEN_AI_CONFIGS.TOP_P,
      frequencyPenalty: DEFAULT_OPEN_AI_CONFIGS.FREQUENCY_PENALTY,
      presencePenalty: DEFAULT_OPEN_AI_CONFIGS.PRESENCE_PENALTY,
    } as LLMOpenAIConfigsType;
  }

  if (provider === PROVIDER_TYPE.ANTHROPIC) {
    // For models requiring exclusive params, clear topP to use temperature by default
    const isExclusive =
      model === PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_1 ||
      model === PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_5 ||
      model === PROVIDER_MODEL_TYPE.CLAUDE_HAIKU_4_5;

    return {
      temperature: DEFAULT_ANTHROPIC_CONFIGS.TEMPERATURE,
      maxCompletionTokens: DEFAULT_ANTHROPIC_CONFIGS.MAX_COMPLETION_TOKENS,
      topP: isExclusive ? undefined : DEFAULT_ANTHROPIC_CONFIGS.TOP_P,
    } as LLMAnthropicConfigsType;
  }

  if (provider === PROVIDER_TYPE.OPEN_ROUTER) {
    return {
      maxTokens: DEFAULT_OPEN_ROUTER_CONFIGS.MAX_TOKENS,
      temperature: DEFAULT_OPEN_ROUTER_CONFIGS.TEMPERATURE,
      topP: DEFAULT_OPEN_ROUTER_CONFIGS.TOP_P,
      topK: DEFAULT_OPEN_ROUTER_CONFIGS.TOP_K,
      frequencyPenalty: DEFAULT_OPEN_ROUTER_CONFIGS.FREQUENCY_PENALTY,
      presencePenalty: DEFAULT_OPEN_ROUTER_CONFIGS.PRESENCE_PENALTY,
      repetitionPenalty: DEFAULT_OPEN_ROUTER_CONFIGS.REPETITION_PENALTY,
      minP: DEFAULT_OPEN_ROUTER_CONFIGS.MIN_P,
      topA: DEFAULT_OPEN_ROUTER_CONFIGS.TOP_A,
    } as LLMOpenRouterConfigsType;
  }

  if (provider === PROVIDER_TYPE.GEMINI) {
    return {
      temperature: DEFAULT_GEMINI_CONFIGS.TEMPERATURE,
      maxCompletionTokens: DEFAULT_GEMINI_CONFIGS.MAX_COMPLETION_TOKENS,
      topP: DEFAULT_GEMINI_CONFIGS.TOP_P,
    } as LLMGeminiConfigsType;
  }

  if (provider === PROVIDER_TYPE.VERTEX_AI) {
    return {
      temperature: DEFAULT_VERTEX_AI_CONFIGS.TEMPERATURE,
      maxCompletionTokens: DEFAULT_VERTEX_AI_CONFIGS.MAX_COMPLETION_TOKENS,
      topP: DEFAULT_VERTEX_AI_CONFIGS.TOP_P,
    } as LLMVertexAIConfigsType;
  }

  if (provider === PROVIDER_TYPE.CUSTOM) {
    return {
      temperature: DEFAULT_CUSTOM_CONFIGS.TEMPERATURE,
      maxCompletionTokens: DEFAULT_CUSTOM_CONFIGS.MAX_COMPLETION_TOKENS,
      topP: DEFAULT_CUSTOM_CONFIGS.TOP_P,
      frequencyPenalty: DEFAULT_CUSTOM_CONFIGS.FREQUENCY_PENALTY,
      presencePenalty: DEFAULT_CUSTOM_CONFIGS.PRESENCE_PENALTY,
    } as LLMCustomConfigsType;
  }

  return {};
};

interface GenerateDefaultPromptParams {
  initPrompt?: Partial<PlaygroundPromptType>;
  setupProviders: PROVIDER_TYPE[];
  lastPickedModel?: PROVIDER_MODEL_TYPE | "";
  providerResolver: ProviderResolver;
  modelResolver: ModelResolver;
}

export const generateDefaultPrompt = ({
  initPrompt = {},
  setupProviders = [],
  lastPickedModel,
  providerResolver,
  modelResolver,
}: GenerateDefaultPromptParams): PlaygroundPromptType => {
  const modelByDefault = modelResolver(lastPickedModel || "", setupProviders);
  const provider = providerResolver(modelByDefault);

  return {
    name: "Prompt",
    messages: [generateDefaultLLMPromptMessage()],
    model: modelByDefault,
    provider,
    configs: getDefaultConfigByProvider(provider, modelByDefault),
    ...initPrompt,
    id: generateRandomString(),
  };
};

export const parseCompletionOutput = (run: RunStreamingReturn) => {
  return (
    run.result ||
    run.opikError ||
    run.providerError ||
    run.pythonProxyError ||
    "The AI provider returned an empty response. Please, try again."
  );
};
