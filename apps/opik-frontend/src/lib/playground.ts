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
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";
import { getDefaultProviderKey } from "@/lib/provider";
import { PROVIDERS } from "@/constants/providers";
import { generateDefaultLLMPromptMessage, getModelProvider } from "@/lib/llm";

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

const getDefaultModel = (
  lastPickedModel: PROVIDER_MODEL_TYPE | "",
  setupProviders: PROVIDER_TYPE[],
) => {
  const lastPickedModelProvider = lastPickedModel
    ? getModelProvider(lastPickedModel)
    : "";

  const isLastPickedModelValid =
    !!lastPickedModelProvider &&
    setupProviders.includes(lastPickedModelProvider);

  if (isLastPickedModelValid) {
    return lastPickedModel;
  }

  const defaultProviderKey = getDefaultProviderKey(setupProviders);

  if (defaultProviderKey) {
    return PROVIDERS[defaultProviderKey]?.defaultModel || "";
  }

  return "";
};

const getDefaultModelConfigs = (model: PROVIDER_MODEL_TYPE | "") => {
  if (!model) {
    return {};
  }

  const modelProvider = getModelProvider(model);

  return modelProvider ? getDefaultConfigByProvider(modelProvider) : {};
};

interface GenerateDefaultPromptParams {
  initPrompt?: Partial<PlaygroundPromptType>;
  setupProviders: PROVIDER_TYPE[];
  lastPickedModel?: PROVIDER_MODEL_TYPE | "";
}

export const generateDefaultPrompt = ({
  initPrompt = {},
  setupProviders = [],
  lastPickedModel,
}: GenerateDefaultPromptParams): PlaygroundPromptType => {
  const modelByDefault = getDefaultModel(lastPickedModel || "", setupProviders);

  return {
    name: "Prompt",
    messages: [generateDefaultLLMPromptMessage()],
    model: modelByDefault,
    configs: getDefaultModelConfigs(modelByDefault),
    ...initPrompt,
    id: generateRandomString(),
  };
};
