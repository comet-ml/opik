import { useQuery } from "@tanstack/react-query";

import { PROVIDERS_OPTIONS } from "@/constants/providers";
import { PROVIDER_MODELS_TYPE, PROVIDER_TYPE } from "@/types/providers";
import { PROVIDERS_KEYS_KEY } from "@/api/api";

const useVllmAIProviderData = () => {
  //   const queryClient = useQueryClient();

  const { data: vllmModels } = useQuery({
    queryKey: [PROVIDERS_KEYS_KEY],
    queryFn: () => {
      const retVal: Partial<PROVIDER_MODELS_TYPE> = {};

      PROVIDERS_OPTIONS.forEach((option) => {
        if (option.value === PROVIDER_TYPE.VLLM) {
          //   retVal[option.value] = option.models.map((m) => ({
          //       value: m.trim() as PROVIDER_MODEL_TYPE,
          //       label: m.trim(),
          //     }));
          // TODO: Build the list of models from the vllm server
          //   }
        }
      });

      return retVal;
    },
  });

  return {
    vllmModels,
  };
};

export default useVllmAIProviderData;
