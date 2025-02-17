import { PlaygroundPromptType } from "@/types/playground";
import { generateRandomString } from "@/lib/utils";
import {
  DEFAULT_ANTHROPIC_CONFIGS,
  DEFAULT_OPEN_AI_CONFIGS,
} from "@/constants/llm";
import {
  LLMAnthropicConfigsType,
  LLMOpenAIConfigsType,
  LLMPromptConfigsType,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";
import { generateDefaultLLMPromptMessage } from "@/lib/llm";
import {
  ModelResolver,
  ProviderResolver,
} from "@/hooks/useLLMProviderModelsData";

export const getDefaultConfigByProvider = (
  provider?: PROVIDER_TYPE | "",
): LLMPromptConfigsType => {
  if (provider === PROVIDER_TYPE.OPEN_AI) {
    return {
      temperature: DEFAULT_OPEN_AI_CONFIGS.TEMPERATURE,
      maxCompletionTokens: DEFAULT_OPEN_AI_CONFIGS.MAX_COMPLETION_TOKENS,
      topP: DEFAULT_OPEN_AI_CONFIGS.TOP_P,
      frequencyPenalty: DEFAULT_OPEN_AI_CONFIGS.FREQUENCY_PENALTY,
      presencePenalty: DEFAULT_OPEN_AI_CONFIGS.PRESENCE_PENALTY,
    } as LLMOpenAIConfigsType;
  }

  if (provider === PROVIDER_TYPE.ANTHROPIC) {
    return {
      temperature: DEFAULT_ANTHROPIC_CONFIGS.TEMPERATURE,
      maxCompletionTokens: DEFAULT_ANTHROPIC_CONFIGS.MAX_COMPLETION_TOKENS,
      topP: DEFAULT_ANTHROPIC_CONFIGS.TOP_P,
    } as LLMAnthropicConfigsType;
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
    configs: getDefaultConfigByProvider(provider),
    ...initPrompt,
    id: generateRandomString(),
  };
};
