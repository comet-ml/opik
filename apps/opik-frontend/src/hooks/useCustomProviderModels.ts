import { useMemo } from "react";
import useProviderKeys from "@/api/provider-keys/useProviderKeys";
import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";
import useAppStore from "@/store/AppStore";
import { DropdownOption } from "@/types/shared";
import { convertCustomProviderModels } from "@/lib/provider";
import { buildCustomModelId } from "@/constants/providers";

const useCustomProviderModels = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { data } = useProviderKeys({
    workspaceName,
  });

  return useMemo(() => {
    const retval: Record<
      PROVIDER_TYPE.CUSTOM,
      DropdownOption<PROVIDER_MODEL_TYPE>[]
    > = {
      [PROVIDER_TYPE.CUSTOM]: [],
    };
    if (data) {
      // Get all custom provider configurations
      const customConfigs = data.content.filter(
        (providerKey) => providerKey.provider === PROVIDER_TYPE.CUSTOM,
      );

      // Combine models from all custom providers
      const allModels: DropdownOption<PROVIDER_MODEL_TYPE>[] = [];

      customConfigs.forEach((customConfig) => {
        if (customConfig.configuration?.models) {
          const providerName = customConfig.keyName || "default";
          const models = customConfig.configuration.models
            .split(",")
            .map((model) => {
              const trimmedModel = model.trim();
              // Build model ID with provider name prefix: {provider-name}/{model-name}
              const modelId = buildCustomModelId(providerName, trimmedModel);

              return {
                value: modelId as PROVIDER_MODEL_TYPE,
                label: convertCustomProviderModels(modelId),
              };
            });

          allModels.push(...models);
        }
      });

      retval[PROVIDER_TYPE.CUSTOM] = allModels;
    }
    return retval;
  }, [data]);
};

export default useCustomProviderModels;
