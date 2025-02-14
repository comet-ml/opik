import { useCallback } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import isEmpty from "lodash/isEmpty";
import isNumber from "lodash/isNumber";
import isArray from "lodash/isArray";

import { safelyParseJSON } from "@/lib/utils";
import {
  LOCAL_PROVIDER_OPTION_TYPE,
  PROVIDERS,
  PROVIDERS_OPTIONS,
} from "@/constants/providers";
import {
  LocalAIProviderData,
  PROVIDER_LOCATION_TYPE,
  PROVIDER_MODEL_TYPE,
  PROVIDER_MODELS_TYPE,
  PROVIDER_TYPE,
  ProviderKey,
} from "@/types/providers";
import { PROVIDERS_KEYS_KEY } from "@/api/api";

const useLocalAIProviderData = () => {
  const queryClient = useQueryClient();

  // workaround to recalculate list of local models every type we have any change in PROVIDERS_KEYS_KEY
  const { data: localModels } = useQuery({
    queryKey: [PROVIDERS_KEYS_KEY],
    queryFn: () => {
      const retVal: Partial<PROVIDER_MODELS_TYPE> = {};

      PROVIDERS_OPTIONS.forEach((option) => {
        if (option.locationType === PROVIDER_LOCATION_TYPE.local) {
          const providerData: LocalAIProviderData | undefined =
            getLocalAIProviderData(option.value);

          if (providerData) {
            retVal[option.value] = providerData.models.split(",").map((m) => ({
              value: m.trim() as PROVIDER_MODEL_TYPE,
              label: m.trim(),
            }));
          }
        }
      });

      return retVal;
    },
  });

  const setLocalAIProviderData = useCallback(
    (provider: PROVIDER_TYPE, data: LocalAIProviderData) => {
      const config = PROVIDERS[provider] as LOCAL_PROVIDER_OPTION_TYPE;
      localStorage.setItem(config.lsKey, JSON.stringify(data));
      queryClient.invalidateQueries({ queryKey: [PROVIDERS_KEYS_KEY] });
    },
    [queryClient],
  );

  const deleteLocalAIProviderData = useCallback(
    (provider: PROVIDER_TYPE) => {
      const config = PROVIDERS[provider] as LOCAL_PROVIDER_OPTION_TYPE;
      localStorage.removeItem(config.lsKey);
      queryClient.invalidateQueries({ queryKey: [PROVIDERS_KEYS_KEY] });
    },
    [queryClient],
  );

  const getLocalAIProviderData = useCallback((provider: PROVIDER_TYPE) => {
    let retVal: LocalAIProviderData | undefined = undefined;
    const config = PROVIDERS[provider] as LOCAL_PROVIDER_OPTION_TYPE;
    const lsData = localStorage.getItem(config.lsKey);

    if (lsData) {
      const providerData: LocalAIProviderData | undefined = safelyParseJSON(
        lsData,
        true,
      );

      retVal = isEmpty(providerData) ? undefined : providerData;
    }

    return retVal;
  }, []);

  const extendWithLocalData = useCallback(
    (data: { total: number; content: ProviderKey[] }) => {
      const localData: ProviderKey[] = [];

      PROVIDERS_OPTIONS.forEach((option) => {
        if (option.locationType === PROVIDER_LOCATION_TYPE.local) {
          const providerData: LocalAIProviderData | undefined =
            getLocalAIProviderData(option.value);

          if (providerData) {
            localData.push({
              id: option.lsKey,
              keyName: option.apiKeyName,
              created_at: providerData.created_at,
              provider: option.value,
            });
          }
        }
      });

      return localData.length > 0 &&
        isNumber(data.total) &&
        isArray(data.content)
        ? {
            content: [...data.content, ...localData],
            total: data.total + localData.length,
          }
        : data;
    },
    [getLocalAIProviderData],
  );

  const getLocalIAProviderURL = useCallback(
    (provider: PROVIDER_TYPE | "") => {
      let retVal: string | undefined = undefined;

      if (
        provider &&
        PROVIDERS[provider].locationType === PROVIDER_LOCATION_TYPE.local
      ) {
        const providerData = getLocalAIProviderData(provider);

        if (providerData) {
          retVal = providerData.url;
        }
      }

      return retVal;
    },
    [getLocalAIProviderData],
  );

  return {
    localModels,
    extendWithLocalData,
    getLocalAIProviderData,
    setLocalAIProviderData,
    deleteLocalAIProviderData,
    getLocalIAProviderURL,
  };
};

export default useLocalAIProviderData;
