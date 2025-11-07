import { useMemo } from "react";
import useProviderKeys from "@/api/provider-keys/useProviderKeys";
import {
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
  ProviderModelsMap,
} from "@/types/providers";
import useAppStore from "@/store/AppStore";
import { convertCustomProviderModel } from "@/lib/provider";

const useCustomProviderModels = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { data } = useProviderKeys({
    workspaceName,
  });

  return useMemo(() => {
    const retval: ProviderModelsMap = {};

    if (data) {
      data.content
        .filter((providerKey) => providerKey.provider === PROVIDER_TYPE.CUSTOM)
        .forEach((customProviderObject) => {
          if (customProviderObject.configuration?.models) {
            retval[customProviderObject.ui_composed_provider] =
              customProviderObject.configuration.models
                .split(",")
                .map((model) => {
                  const trimmedModel = model.trim();

                  return {
                    value: trimmedModel as PROVIDER_MODEL_TYPE,
                    label: convertCustomProviderModel(
                      trimmedModel,
                      customProviderObject.provider_name ?? "",
                    ),
                  };
                });
          }
        });
    }

    return retval;
  }, [data]);
};

export default useCustomProviderModels;
