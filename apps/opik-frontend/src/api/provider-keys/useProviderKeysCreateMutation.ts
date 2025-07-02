import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import api, {
  PROVIDER_KEYS_REST_ENDPOINT,
  PROVIDERS_KEYS_KEY,
} from "@/api/api";
import { AxiosError } from "axios";
import { useToast } from "@/components/ui/use-toast";
import { ProviderKeyWithAPIKey, PROVIDER_TYPE } from "@/types/providers";

type UseProviderKeysCreateMutationParams = {
  providerKey: Partial<ProviderKeyWithAPIKey>;
};

const useProviderKeysCreateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      providerKey,
    }: UseProviderKeysCreateMutationParams) => {
      const config: Record<string, unknown> = {};
      if (providerKey?.location) {
        config.location = providerKey.location;
      }

      if (
        providerKey.provider === PROVIDER_TYPE.VLLM &&
        typeof providerKey.models === 'string'
      ) {
        config.models = providerKey.models
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
      } else if (providerKey?.models) {
        config.models = providerKey.models;
      }

      const configuration = Object.keys(config).length
        ? { configuration: config }
        : {};

      const { data } = await api.post(PROVIDER_KEYS_REST_ENDPOINT, {
        provider: providerKey.provider,
        api_key: providerKey.apiKey,
        base_url: providerKey.url,
        ...configuration,
      });

      return data;
    },
    onError: (error: AxiosError) => {
      const message = get(
        error,
        ["response", "data", "errors", "0"],
        error.message,
      );

      toast({
        title: "Error",
        description: message,
        variant: "destructive",
      });
    },
    onSettled: () => {
      return queryClient.invalidateQueries({
        queryKey: [PROVIDERS_KEYS_KEY],
      });
    },
  });
};

export default useProviderKeysCreateMutation;
