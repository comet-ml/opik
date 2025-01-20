import { PlaygroundPromptType } from "@/types/playground";
import { generateRandomString } from "@/lib/utils";
import {
  DEFAULT_ANTHROPIC_CONFIGS,
  DEFAULT_OPEN_AI_CONFIGS,
} from "@/constants/llm";
import {
  LLMAnthropicConfigsType,
  LLMGeminiConfigsType,
  LLMOpenAIConfigsType,
  LLMPromptConfigsType,
  PROVIDER_TYPE,
} from "@/types/providers";
import { getDefaultProviderKey } from "@/lib/provider";
import { PROVIDERS } from "@/constants/providers";
import { generateDefaultLLMPromptMessage } from "@/lib/llm";

export const getDefaultConfigByProvider = (
  provider: PROVIDER_TYPE,
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

  if (provider === PROVIDER_TYPE.GEMINI) {
    return {
      temperature: DEFAULT_ANTHROPIC_CONFIGS.TEMPERATURE,
      maxCompletionTokens: DEFAULT_ANTHROPIC_CONFIGS.MAX_COMPLETION_TOKENS,
      topP: DEFAULT_ANTHROPIC_CONFIGS.TOP_P,
    } as LLMGeminiConfigsType;
  }

  return {};
};

interface GenerateDefaultPromptParams {
  initPrompt?: Partial<PlaygroundPromptType>;
  setupProviders?: PROVIDER_TYPE[];
}

export const generateDefaultPrompt = ({
  initPrompt = {},
  setupProviders = [],
}: GenerateDefaultPromptParams): PlaygroundPromptType => {
  const defaultProviderKey = getDefaultProviderKey(setupProviders);
  const defaultModel = defaultProviderKey
    ? PROVIDERS[defaultProviderKey]?.defaultModel || ""
    : "";

  return {
    name: "Prompt",
    messages: [generateDefaultLLMPromptMessage()],
    model: defaultModel,
    configs: defaultProviderKey
      ? getDefaultConfigByProvider(defaultProviderKey)
      : {},
    ...initPrompt,
    id: generateRandomString(),
  };
};
