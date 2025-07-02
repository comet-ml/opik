import {
  PROVIDER_TYPE,
  ProviderKey,
  ProviderKeyWithAPIKey,
} from "@/types/providers";
import useProviderKeys from "@/api/provider-keys/useProviderKeys";
import useAppStore from "@/store/AppStore";
import { useCallback, useEffect, useState } from "react";

const useVllmAIProviderData = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { data } = useProviderKeys({
    workspaceName,
  });
  const [vllmModels, setVllmModels] = useState<
    { [key: string]: { value: string; label: string }[] } | undefined
  >(undefined);

  useEffect(() => {
    if (data) {
      const vllmConfig = data.content.find(
        (providerKey) => providerKey.provider === PROVIDER_TYPE.VLLM,
      );

      if (vllmConfig) {
        const _vllmConfig = vllmConfig as ProviderKey & { base_url: string, configuration: {models: string} };
        const models = _vllmConfig.configuration.models
          .split(',')
          .map((model) => {
            const trimmedModel = model.trim();
            const prefix = `${PROVIDER_TYPE.VLLM}/`;
            const label = trimmedModel.startsWith(prefix) ? trimmedModel.replace(prefix, '') : trimmedModel;
            return { value: trimmedModel, label };
          });
        setVllmModels({ [PROVIDER_TYPE.VLLM]: models });
      }
    }
  }, [data]);

  const getVllmAIProviderData = useCallback(() => {
    let retVal: ProviderKeyWithAPIKey | undefined = undefined;
    if (data) {
      const vllmConfig = data.content.find(
        (providerKey) => providerKey.provider === PROVIDER_TYPE.VLLM,
      );

      if (vllmConfig) {
        // Not using spread, we don't need all the properties
        const _vllmConfig = vllmConfig as ProviderKey & { base_url: string, configuration: {models: string} };
        const models = _vllmConfig.configuration.models
          .split(',')
          .map((model) => model.trim())
          .filter(Boolean)
          .map((model) => {
            if (model.startsWith(`${PROVIDER_TYPE.VLLM}/`)) {
              return model;
            }
            return `${PROVIDER_TYPE.VLLM}/${model}`;
          })
          .join(',');

        retVal = {
          id: _vllmConfig.id,
          keyName: _vllmConfig.keyName,
          provider: _vllmConfig.provider,
          created_at: _vllmConfig.created_at,
          url: _vllmConfig.base_url,
          apiKey: "*****",
          models: models,
        };
      }
    }
    return retVal;
  }, [data]);

  return {
    vllmModels,
    getVllmAIProviderData,
  };
};

export default useVllmAIProviderData;
