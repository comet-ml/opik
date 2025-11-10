import {
  PROVIDER_TYPE,
  ProviderObject,
  COMPOSED_PROVIDER_TYPE,
} from "@/types/providers";
import {
  CUSTOM_PROVIDER_MODEL_PREFIX,
  LEGACY_CUSTOM_PROVIDER_NAME,
  PROVIDERS,
} from "@/constants/providers";

export const getProviderDisplayName = (providerKey: ProviderObject) => {
  const { provider, provider_name } = providerKey;
  if (provider === PROVIDER_TYPE.CUSTOM) {
    return provider_name ? provider_name : LEGACY_CUSTOM_PROVIDER_NAME;
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
  return providerType === PROVIDER_TYPE.CUSTOM && providerName
    ? `${PROVIDER_TYPE.CUSTOM}:${providerName}`
    : providerType;
};

export const parseComposedProviderType = (provider: COMPOSED_PROVIDER_TYPE) => {
  return provider.startsWith(PROVIDER_TYPE.CUSTOM)
    ? PROVIDER_TYPE.CUSTOM
    : (provider as PROVIDER_TYPE);
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
