import {
  PROVIDER_TYPE,
  ProviderObject,
  COMPOSED_PROVIDER_TYPE,
  PROVIDER_MODEL_TYPE,
  OPENAI_PIPELINE_MODE_VALUES,
  OpenAiPipelineMode,
} from "@/types/providers";
import {
  CUSTOM_PROVIDER_MODEL_PREFIX,
  LEGACY_CUSTOM_PROVIDER_NAME,
  PROVIDERS,
} from "@/constants/providers";
import {
  getLatestProviderModelsSnapshot,
  KNOWN_PROVIDER_TYPES,
} from "@/lib/modelRegistryStore";

export const getProviderDisplayName = (providerKey: ProviderObject) => {
  const { provider, provider_name } = providerKey;

  if (provider === PROVIDER_TYPE.CUSTOM) {
    return provider_name ? provider_name : LEGACY_CUSTOM_PROVIDER_NAME;
  }

  if (provider === PROVIDER_TYPE.BEDROCK || provider === PROVIDER_TYPE.OLLAMA) {
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
    [
      PROVIDER_TYPE.CUSTOM,
      PROVIDER_TYPE.BEDROCK,
      PROVIDER_TYPE.OLLAMA,
    ].includes(providerType)
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
  if (provider.startsWith(PROVIDER_TYPE.OLLAMA)) {
    return PROVIDER_TYPE.OLLAMA;
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
  const snapshot = getLatestProviderModelsSnapshot();
  for (const [providerType, models] of Object.entries(snapshot)) {
    // Only trust keys that match a known PROVIDER_TYPE. A YAML-backed
    // provider key we don't understand (e.g. future provider added CDN-side
    // before the FE ships matching metadata) shouldn't be returned as if it
    // were a valid enum — callers read PROVIDERS[providerType] downstream.
    if (
      KNOWN_PROVIDER_TYPES.has(providerType) &&
      models.some((m) => m.value === model)
    ) {
      return providerType as PROVIDER_TYPE;
    }
  }
  return PROVIDER_TYPE.OPEN_AI;
};

// Default pipeline mode applied as a fallback in form defaults, resets, and save payloads.
// Centralised here so changing the default requires editing only one place.
export const DEFAULT_OPENAI_PIPELINE_MODE: OpenAiPipelineMode =
  "chat_completions_api";

/**
 * Normalises a backend-stored {@code openai_pipeline_mode} string into a typed
 * {@link OpenAiPipelineMode}. The backend's {@code OpenAIClientGenerator.extractApiPipelineMode}
 * accepts any casing (it uppercases before enum lookup), so the persisted value could be either
 * lowercase or uppercase depending on how it was written (UI vs direct REST). The form schema is
 * strict-cased lowercase, so we lowercase here and reject anything that isn't one of the known
 * values — falling back to {@link DEFAULT_OPENAI_PIPELINE_MODE}. Keeps the Select always pointing
 * at a valid option and prevents Zod from blocking submit on legacy/odd-cased values.
 */
export const normalizeOpenAiPipelineMode = (
  value: string | undefined | null,
): OpenAiPipelineMode => {
  if (!value) return DEFAULT_OPENAI_PIPELINE_MODE;
  const lowered = value.toLowerCase();
  return (OPENAI_PIPELINE_MODE_VALUES as readonly string[]).includes(lowered)
    ? (lowered as OpenAiPipelineMode)
    : DEFAULT_OPENAI_PIPELINE_MODE;
};
