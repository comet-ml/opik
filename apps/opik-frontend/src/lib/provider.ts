import {
  PROVIDER_TYPE,
  ProviderObject,
  COMPOSED_PROVIDER_TYPE,
  PROVIDER_MODEL_TYPE,
} from "@/types/providers";
import {
  CUSTOM_PROVIDER_MODEL_PREFIX,
  LEGACY_CUSTOM_PROVIDER_NAME,
  PROVIDERS,
} from "@/constants/providers";
import { PROVIDER_MODELS } from "@/hooks/useLLMProviderModelsData";

export const getProviderDisplayName = (providerKey: ProviderObject) => {
  const { provider, provider_name } = providerKey;
  if (provider === PROVIDER_TYPE.CUSTOM) {
    return provider_name ? provider_name : LEGACY_CUSTOM_PROVIDER_NAME;
  }

  if (provider === PROVIDER_TYPE.BEDROCK) {
    return provider_name ?? PROVIDERS[provider]?.label ?? "";
  }

  return PROVIDERS[provider]?.label ?? "";
};

export const getProviderIcon = (providerKey: ProviderObject) => {
  return PROVIDERS[providerKey.provider]?.icon;
};

export const buildComposedProviderKey = (
  providerType: PROVIDER_TYPE,
  providerName?: string,
): COMPOSED_PROVIDER_TYPE => {
  if (
    providerName &&
    [PROVIDER_TYPE.CUSTOM, PROVIDER_TYPE.BEDROCK].includes(providerType)
  ) {
    return `${providerType}:${providerName}`;
  }
  return providerType;
};

export const parseComposedProviderType = (provider: COMPOSED_PROVIDER_TYPE) => {
  if (provider.startsWith(PROVIDER_TYPE.CUSTOM)) {
    return PROVIDER_TYPE.CUSTOM;
  }
  if (provider.startsWith(PROVIDER_TYPE.BEDROCK)) {
    return PROVIDER_TYPE.BEDROCK;
  }
  return provider as PROVIDER_TYPE;
};

export const convertCustomProviderModels = (
  models: string,
  providerName: string = "",
  toAPI = false,
) => {
  return models
    .split(",")
    .map((model) => model.trim())
    .filter(Boolean)
    .map((model) => convertCustomProviderModel(model, providerName, toAPI))
    .join(",");
};

export const convertCustomProviderModel = (
  model: string,
  providerName: string,
  toAPI = false,
) => {
  const prefix = providerName
    ? `${CUSTOM_PROVIDER_MODEL_PREFIX}/${providerName}/`
    : `${CUSTOM_PROVIDER_MODEL_PREFIX}/`;

  if (toAPI) {
    return prefix + model;
  } else {
    return model.startsWith(prefix) ? model.replace(prefix, "") : model;
  }
};

export const getProviderFromModel = (
  model: PROVIDER_MODEL_TYPE,
): PROVIDER_TYPE => {
  for (const [providerType, models] of Object.entries(PROVIDER_MODELS)) {
    if (models.some((m) => m.value === model)) {
      return providerType as PROVIDER_TYPE;
    }
  }
  return PROVIDER_TYPE.OPEN_AI;
};
