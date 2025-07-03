import { useMemo } from "react";
import useProviderKeys from "@/api/provider-keys/useProviderKeys";
import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";
import useAppStore from "@/store/AppStore";
import { DropdownOption } from "@/types/shared";
import { convertCustomProviderModels } from "@/lib/provider";

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
      const customConfig = data.content.find(
        (providerKey) => providerKey.provider === PROVIDER_TYPE.CUSTOM,
      );

      if (customConfig && customConfig.configuration?.models) {
        retval[PROVIDER_TYPE.CUSTOM] = customConfig.configuration.models
          .split(",")
          .map((model) => {
            const trimmedModel = model.trim();

            return {
              value: trimmedModel as PROVIDER_MODEL_TYPE,
              label: convertCustomProviderModels(trimmedModel),
            };
          });
      }
    }
    return retval;
  }, [data]);
};

export default useCustomProviderModels;
