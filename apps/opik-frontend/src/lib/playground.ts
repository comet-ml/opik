import {
  PLAYGROUND_MESSAGE_ROLE,
  PlaygroundMessageType,
  PlaygroundPromptConfigsType,
  PlaygroundPromptType,
} from "@/types/playground";
import { generateRandomString } from "@/lib/utils";
import {
  DEFAULT_OPEN_AI_CONFIGS,
  PROVIDER_MODELS,
} from "@/constants/playground";
import {
  PlaygroundOpenAIConfigsType,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";
import { getDefaultProviderKey } from "@/lib/provider";
import { PROVIDERS } from "@/constants/providers";

export const generateDefaultPlaygroundPromptMessage = (
  message: Partial<PlaygroundMessageType> = {},
): PlaygroundMessageType => {
  return {
    content: "",
    role: PLAYGROUND_MESSAGE_ROLE.system,
    ...message,
    id: generateRandomString(),
  };
};

export const getModelProvider = (
  modelName: PROVIDER_MODEL_TYPE,
): PROVIDER_TYPE | "" => {
  const provider = Object.entries(PROVIDER_MODELS).find(
    ([providerName, providerModels]) => {
      if (providerModels.find((pm) => modelName === pm.value)) {
        return providerName;
      }

      return false;
    },
  );

  if (!provider) {
    return "";
  }

  const [providerName] = provider;

  return providerName as PROVIDER_TYPE;
};

export const getDefaultConfigByProvider = (
  provider: PROVIDER_TYPE,
): PlaygroundPromptConfigsType => {
  if (provider === PROVIDER_TYPE.OPEN_AI) {
    return {
      temperature: DEFAULT_OPEN_AI_CONFIGS.TEMPERATURE,
      maxCompletionTokens: DEFAULT_OPEN_AI_CONFIGS.MAX_COMPLETION_TOKENS,
      topP: DEFAULT_OPEN_AI_CONFIGS.TOP_P,
      frequencyPenalty: DEFAULT_OPEN_AI_CONFIGS.FREQUENCY_PENALTY,
      presencePenalty: DEFAULT_OPEN_AI_CONFIGS.PRESENCE_PENALTY,
    } as PlaygroundOpenAIConfigsType;
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
    ? PROVIDERS[defaultProviderKey].defaultModel
    : "";

  return {
    name: "Prompt",
    messages: [generateDefaultPlaygroundPromptMessage()],
    model: defaultModel,
    configs: defaultProviderKey
      ? getDefaultConfigByProvider(defaultProviderKey)
      : {},
    ...initPrompt,
    id: generateRandomString(),
  };
};
