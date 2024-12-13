import {
  ProviderMessageType,
  PLAYGROUND_MESSAGE_ROLE,
  PLAYGROUND_MODEL_TYPE,
  PLAYGROUND_PROVIDERS_TYPES,
  PlaygroundMessageType,
  PlaygroundPromptConfigsType,
  PlaygroundOpenAIConfigsType,
} from "@/types/playgroundPrompts";
import { generateRandomString } from "@/lib/utils";
import {
  DEFAULT_OPEN_AI_CONFIGS,
  PLAYGROUND_MODELS,
} from "@/constants/playground";
import PlaygroundPromptMessage from "@/components/pages/PlaygroundPage/PlaygroundPrompt/PlaygroundPromptMessages/PlaygroundPromptMessage";
import OpenAIModelSettings from "@/components/pages/PlaygroundPage/PlaygroundPrompt/PromptModelSettings/providerSettings/OpenAIModelSettings";

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
  modelName: PLAYGROUND_MODEL_TYPE,
): PLAYGROUND_PROVIDERS_TYPES | "" => {
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

  return providerName as PLAYGROUND_PROVIDERS_TYPES;
};

export const getDefaultConfigByProvider = (
  provider: PLAYGROUND_PROVIDERS_TYPES,
): PlaygroundPromptConfigsType => {
  if (provider === PLAYGROUND_PROVIDERS_TYPES.OpenAI) {
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
