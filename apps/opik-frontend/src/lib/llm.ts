import { LLM_MESSAGE_ROLE, LLMMessage, ProviderMessageType } from "@/types/llm";
import { generateRandomString } from "@/lib/utils";
import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";
import { PROVIDER_MODELS } from "@/constants/llm";

export const generateDefaultLLMPromptMessage = (
  message: Partial<LLMMessage> = {},
): LLMMessage => {
  return {
    content: "",
    role: LLM_MESSAGE_ROLE.system,
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

export const getNextMessageType = (
  previousMessage: LLMMessage,
): LLM_MESSAGE_ROLE => {
  if (previousMessage.role === LLM_MESSAGE_ROLE.user) {
    return LLM_MESSAGE_ROLE.assistant;
  }

  return LLM_MESSAGE_ROLE.user;
};

export const convertLLMToProviderMessages = (messages: LLMMessage[]) =>
  messages.map((m) => ({ content: m.content, role: m.role }));

export const convertProviderToLLMMessages = (messages: ProviderMessageType[]) =>
  messages.map((m) => ({ ...m, id: generateRandomString() }));
