import { useMemo } from "react";
import useProviderKeys from "@/api/provider-keys/useProviderKeys";
import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";
import useAppStore from "@/store/AppStore";
import { DropdownOption } from "@/types/shared";
import { 
  buildCustomModelId, 
  CUSTOM_PROVIDER_MODEL_PREFIX,
  LEGACY_CUSTOM_PROVIDER_NAME 
} from "@/constants/providers";

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
          const providerName = customConfig.keyName || LEGACY_CUSTOM_PROVIDER_NAME;
          const isLegacyProvider = providerName === LEGACY_CUSTOM_PROVIDER_NAME;
          
          const models = customConfig.configuration.models
            .split(",")
            .map((model) => {
              const trimmedModel = model.trim();
              // Build model ID with provider name prefix: {provider}/{provider-name?}/{model-name}
              const modelId = isLegacyProvider
                ? trimmedModel
                : `${CUSTOM_PROVIDER_MODEL_PREFIX}/${buildCustomModelId(providerName, trimmedModel)}`;

              // Display label strips the "custom-llm/" prefix for better UX
              const displayLabel = modelId.startsWith(`${CUSTOM_PROVIDER_MODEL_PREFIX}/`)
                ? modelId.substring(`${CUSTOM_PROVIDER_MODEL_PREFIX}/`.length)
                : modelId;

              return {
                value: modelId as PROVIDER_MODEL_TYPE,
                label: displayLabel,
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
