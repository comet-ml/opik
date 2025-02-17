import { useCallback } from "react";
import first from "lodash/first";
import {
  PROVIDER_LOCATION_TYPE,
  PROVIDER_MODEL_TYPE,
  PROVIDER_MODELS_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";
import useLocalAIProviderData from "@/hooks/useLocalAIProviderData";
import { getDefaultProviderKey } from "@/lib/provider";
import { PROVIDERS } from "@/constants/providers";

export type ProviderResolver = (
  modelName?: PROVIDER_MODEL_TYPE | "",
) => PROVIDER_TYPE | "";

export type ModelResolver = (
  lastPickedModel: PROVIDER_MODEL_TYPE | "",
  setupProviders: PROVIDER_TYPE[],
  preferredProvider?: PROVIDER_TYPE | "",
) => PROVIDER_MODEL_TYPE | "";

export const PROVIDER_MODELS: PROVIDER_MODELS_TYPE = {
  [PROVIDER_TYPE.OPEN_AI]: [
    // GPT-4.0 Models
    {
      value: PROVIDER_MODEL_TYPE.GPT_4O,
      label: "GPT 4o",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_4O_MINI,
      label: "GPT 4o Mini",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_4O_MINI_2024_07_18,
      label: "GPT 4o Mini 2024-07-18",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_4O_2024_08_06,
      label: "GPT 4o 2024-08-06",
      structuredOutput: true,
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_4O_2024_05_13,
      label: "GPT 4o 2024-05-13",
    },

    // GPT-4 Models
    {
      value: PROVIDER_MODEL_TYPE.GPT_4_TURBO,
      label: "GPT 4 Turbo",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_4,
      label: "GPT 4",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_4_TURBO_PREVIEW,
      label: "GPT 4 Turbo Preview",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_4_TURBO_2024_04_09,
      label: "GPT 4 Turbo 2024-04-09",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_4_1106_PREVIEW,
      label: "GPT 4 1106 Preview",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_4_0613,
      label: "GPT 4 0613",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_4_0125_PREVIEW,
      label: "GPT 4 0125 Preview",
    },

    // GPT-3.5 Models
    {
      value: PROVIDER_MODEL_TYPE.GPT_3_5_TURBO,
      label: "GPT 3.5 Turbo",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_3_5_TURBO_1106,
      label: "GPT 3.5 Turbo 1106",
    },
    {
      value: PROVIDER_MODEL_TYPE.GPT_3_5_TURBO_0125,
      label: "GPT 3.5 Turbo 0125",
    },
  ],

  [PROVIDER_TYPE.ANTHROPIC]: [
    {
      value: PROVIDER_MODEL_TYPE.CLAUDE_3_5_SONNET_20241022,
      label: "Claude 3.5 Sonnet 2024-10-22",
    },
    {
      value: PROVIDER_MODEL_TYPE.CLAUDE_3_5_HAIKU_20241022,
      label: "Claude 3.5 Haiku 2024-10-22",
    },
    {
      value: PROVIDER_MODEL_TYPE.CLAUDE_3_5_SONNET_20240620,
      label: "Claude 3.5 Sonnet 2024-06-20",
    },
    {
      value: PROVIDER_MODEL_TYPE.CLAUDE_3_OPUS_20240229,
      label: "Claude 3 Opus 2024-02-29",
    },
    {
      value: PROVIDER_MODEL_TYPE.CLAUDE_3_SONNET_20240229,
      label: "Claude 3 Sonnet 2024-02-29",
    },
    {
      value: PROVIDER_MODEL_TYPE.CLAUDE_3_HAIKU_20240307,
      label: "Claude 3 Haiku 2024-03-07",
    },
    {
      value: PROVIDER_MODEL_TYPE.CLAUDE_3_5_SONNET_LATEST,
      label: "Claude 3.5 Sonnet Latest",
    },
    {
      value: PROVIDER_MODEL_TYPE.CLAUDE_3_5_HAIKU_LATEST,
      label: "Claude 3.5 Haiku Latest",
    },
    {
      value: PROVIDER_MODEL_TYPE.CLAUDE_3_OPUS_LATEST,
      label: "Claude 3 Opus Latest",
    },
  ],
  [PROVIDER_TYPE.OLLAMA]: [
    // the list will be full filled base on data in localstorage
  ],
};

const useLLMProviderModelsData = () => {
  const { localModels, getLocalAIProviderData } = useLocalAIProviderData();

  const getProviderModels = useCallback(() => {
    return { ...PROVIDER_MODELS, ...localModels };
  }, [localModels]);

  const calculateModelProvider = useCallback(
    (modelName?: PROVIDER_MODEL_TYPE | ""): PROVIDER_TYPE | "" => {
      if (!modelName) {
        return "";
      }

      const provider = Object.entries(getProviderModels()).find(
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
    },
    [getProviderModels],
  );

  const calculateDefaultModel = useCallback(
    (
      lastPickedModel: PROVIDER_MODEL_TYPE | "",
      setupProviders: PROVIDER_TYPE[],
      preferredProvider?: PROVIDER_TYPE | "",
    ) => {
      const lastPickedModelProvider = calculateModelProvider(lastPickedModel);

      const isLastPickedModelValid =
        !!lastPickedModelProvider &&
        setupProviders.includes(lastPickedModelProvider);

      if (isLastPickedModelValid) {
        return lastPickedModel;
      }

      const provider =
        preferredProvider ?? getDefaultProviderKey(setupProviders);

      if (provider) {
        if (PROVIDERS[provider].locationType === PROVIDER_LOCATION_TYPE.local) {
          return (
            (first(
              (getLocalAIProviderData(provider)?.models || "").split(","),
            )?.trim() as PROVIDER_MODEL_TYPE) ?? ""
          );
        } else {
          return PROVIDERS[provider].defaultModel;
        }
      }

      return "";
    },
    [calculateModelProvider, getLocalAIProviderData],
  );

  return {
    getProviderModels,
    calculateModelProvider,
    calculateDefaultModel,
  };
};

export default useLLMProviderModelsData;
