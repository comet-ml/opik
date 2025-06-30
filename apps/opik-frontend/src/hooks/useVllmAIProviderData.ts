import {
  PROVIDER_TYPE,
  ProviderKey,
  ProviderKeyWithAPIKey,
} from "@/types/providers";
import useProviderKeys from "@/api/provider-keys/useProviderKeys";
import useAppStore from "@/store/AppStore";
import { useCallback, useEffect, useState } from "react";
import useVllmModels from "@/api/vllm/useVllmModels";

const useVllmAIProviderData = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { data } = useProviderKeys({
    workspaceName,
  });
  const [baseUrl, setBaseUrl] = useState<string | undefined>(undefined);
  const { data: vllmModels } = useVllmModels(baseUrl);

  useEffect(() => {
    if (data) {
      const vllmConfig = data.content.find(
        (providerKey) => providerKey.provider === PROVIDER_TYPE.VLLM,
      );

      if (vllmConfig) {
        const _vllmConfig = vllmConfig as ProviderKey & { base_url: string };
        setBaseUrl(_vllmConfig.base_url);
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
        const _vllmConfig = vllmConfig as ProviderKey & { base_url: string };
        retVal = {
          id: _vllmConfig.id,
          keyName: _vllmConfig.keyName,
          provider: _vllmConfig.provider,
          created_at: _vllmConfig.created_at,
          url: _vllmConfig.base_url,
          apiKey: "*****",
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
