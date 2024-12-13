import {
  ProviderMessageType,
  PLAYGROUND_MESSAGE_ROLE,
  PLAYGROUND_MODEL,
  PLAYGROUND_PROVIDER,
  PlaygroundMessageType,
  PlaygroundPromptConfigsType,
  PlaygroundOpenAIConfigsType,
} from "@/types/playground";
import { generateRandomString } from "@/lib/utils";
import {
  DEFAULT_OPEN_AI_CONFIGS,
  PLAYGROUND_MODELS,
} from "@/constants/playground";

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
  modelName: PLAYGROUND_MODEL,
): PLAYGROUND_PROVIDER | "" => {
  const provider = Object.entries(PLAYGROUND_MODELS).find(
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

  return providerName as PLAYGROUND_PROVIDER;
};

export const getDefaultConfigByProvider = (
  provider: PLAYGROUND_PROVIDER,
): PlaygroundPromptConfigsType => {
  if (provider === PLAYGROUND_PROVIDER.OpenAI) {
    return {
      temperature: DEFAULT_OPEN_AI_CONFIGS.TEMPERATURE,
      maxTokens: DEFAULT_OPEN_AI_CONFIGS.MAX_TOKENS,
      topP: DEFAULT_OPEN_AI_CONFIGS.TOP_P,
      stop: DEFAULT_OPEN_AI_CONFIGS.STOP,
      frequencyPenalty: DEFAULT_OPEN_AI_CONFIGS.FREQUENCY_PENALTY,
      presencePenalty: DEFAULT_OPEN_AI_CONFIGS.PRESENCE_PENALTY,
    } as PlaygroundOpenAIConfigsType;
  }
  return {};
};

export const transformMessageIntoProviderMessage = (
  message: PlaygroundMessageType,
): ProviderMessageType => {
  return {
    role: message.role,
    content: message.content,
  };
};
