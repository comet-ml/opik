import { useMemo } from "react";
import useProviderKeys from "@/api/provider-keys/useProviderKeys";
import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";
import useAppStore from "@/store/AppStore";
import { DropdownOption } from "@/types/shared";
import {
  buildCustomModelId,
  CUSTOM_PROVIDER_MODEL_PREFIX,
  LEGACY_CUSTOM_PROVIDER_NAME,
} from "@/constants/providers";

export interface CustomProviderModels {
  [providerKey: string]: DropdownOption<PROVIDER_MODEL_TYPE>[];
}

const useCustomProviderModels = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { data } = useProviderKeys({
    workspaceName,
  });

  return useMemo(() => {
    const retval: CustomProviderModels = {};

    if (data) {
      // Get all custom provider configurations
      const customConfigs = data.content.filter(
        (providerKey) => providerKey.provider === PROVIDER_TYPE.CUSTOM,
      );

      // Create a separate entry for each custom provider
      customConfigs.forEach((customConfig) => {
        if (customConfig.configuration?.models) {
          const providerName =
            customConfig.provider_name || LEGACY_CUSTOM_PROVIDER_NAME;
          const isLegacyProvider = !customConfig.provider_name;

          // Use a unique key for each custom provider
          // For legacy providers: "custom-llm"
          // For new providers: "custom-llm:{providerName}"
          const providerKey = isLegacyProvider
            ? PROVIDER_TYPE.CUSTOM
            : `${PROVIDER_TYPE.CUSTOM}:${providerName}`;

          const models = customConfig.configuration.models
            .split(",")
            .map((model) => {
              let trimmedModel = model.trim();

              // Strip "custom-llm/" prefix if present (backward compatibility)
              if (trimmedModel.startsWith(`${CUSTOM_PROVIDER_MODEL_PREFIX}/`)) {
                trimmedModel = trimmedModel.substring(
                  `${CUSTOM_PROVIDER_MODEL_PREFIX}/`.length,
                );
              }

              // For legacy providers (no providerName), keep the model as-is
              // For new providers, prepend "custom-llm/{providerName}/"
              const modelId = isLegacyProvider
                ? `${CUSTOM_PROVIDER_MODEL_PREFIX}/${trimmedModel}`
                : `${CUSTOM_PROVIDER_MODEL_PREFIX}/${buildCustomModelId(
                    providerName,
                    trimmedModel,
                  )}`;

              // Display label strips the "custom-llm/" prefix for better UX
              const displayLabel = modelId.startsWith(
                `${CUSTOM_PROVIDER_MODEL_PREFIX}/`,
              )
                ? modelId.substring(`${CUSTOM_PROVIDER_MODEL_PREFIX}/`.length)
                : modelId;

              return {
                value: modelId as PROVIDER_MODEL_TYPE,
                label: displayLabel,
              };
            });

          retval[providerKey] = models;
        }
      });
    }

    return retval;
  }, [data]);
};

export default useCustomProviderModels;
