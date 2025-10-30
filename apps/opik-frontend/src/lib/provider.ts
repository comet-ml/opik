import first from "lodash/first";
import { PROVIDER_TYPE, ProviderKey } from "@/types/providers";
import { CUSTOM_PROVIDER_MODEL_PREFIX, PROVIDERS } from "@/constants/providers";

export const getDefaultProviderKey = (providers: PROVIDER_TYPE[]) => {
  return first(providers) || "";
};

export const convertCustomProviderModels = (models: string, toAPI = false) => {
  return models
    .split(",")
    .map((model) => model.trim())
    .filter(Boolean)
    .map((model) => convertCustomProviderModel(model, toAPI))
    .join(",");
};

export const convertCustomProviderModel = (model: string, toAPI = false) => {
  const prefix = `${CUSTOM_PROVIDER_MODEL_PREFIX}/`;
  if (toAPI) {
    return prefix + model;
  } else {
    return model.startsWith(prefix) ? model.replace(prefix, "") : model;
  }
};
