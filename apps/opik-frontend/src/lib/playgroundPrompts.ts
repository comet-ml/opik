import {
  PLAYGROUND_MESSAGE_TYPE,
  PLAYGROUND_MODEL_TYPE,
  PLAYGROUND_PROVIDERS_TYPES,
  PlaygroundMessageType,
} from "@/types/playgroundPrompts";
import { generateRandomString } from "@/lib/utils";
import { PLAYGROUND_MODELS } from "@/constants/playground";

export const generateDefaultPlaygroundPromptMessage = (
  message: Partial<PlaygroundMessageType> = {},
): PlaygroundMessageType => {
  return {
    text: "",
    type: PLAYGROUND_MESSAGE_TYPE.system,

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
