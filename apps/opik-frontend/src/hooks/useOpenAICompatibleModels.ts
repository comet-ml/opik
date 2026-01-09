import { useMemo } from "react";
import useProviderKeys from "@/api/provider-keys/useProviderKeys";
import {
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
  ProviderModelsMap,
} from "@/types/providers";
import useAppStore from "@/store/AppStore";
import { convertCustomProviderModel } from "@/lib/provider";

/**
 * Hook to fetch and transform models from OpenAI-compatible providers.
 * This includes both Custom LLM and Bedrock providers, which share the same
 * OpenAI-compatible API format for model configuration.
 */
const useOpenAICompatibleModels = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { data } = useProviderKeys({
    workspaceName,
  });

  return useMemo(() => {
    const retval: ProviderModelsMap = {};

    if (data) {
      data.content
        .filter(
          (providerKey) =>
            providerKey.provider === PROVIDER_TYPE.CUSTOM ||
            providerKey.provider === PROVIDER_TYPE.BEDROCK,
        )
        .forEach((providerObject) => {
          if (providerObject.configuration?.models) {
            retval[providerObject.ui_composed_provider] =
              providerObject.configuration.models.split(",").map((model) => {
                const trimmedModel = model.trim();

                return {
                  value: trimmedModel as PROVIDER_MODEL_TYPE,
                  label: convertCustomProviderModel(
                    trimmedModel,
                    providerObject.provider_name ?? "",
                  ),
                };
              });
          }
        });
    }

    return retval;
  }, [data]);
};

export default useOpenAICompatibleModels;
