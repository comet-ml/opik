import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";

import api, {
  PROVIDER_KEYS_REST_ENDPOINT,
  PROVIDERS_KEYS_KEY,
} from "@/api/api";
import { useToast } from "@/components/ui/use-toast";
import { ProviderKeyWithAPIKey } from "@/types/providers";

type UseProviderKeyUpdateMutationParams = {
  providerKey: Partial<ProviderKeyWithAPIKey>;
};

const useProviderKeysUpdateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ providerKey }: UseProviderKeyUpdateMutationParams) => {
      const configuration = providerKey?.location
        ? { configuration: { location } }
        : {};

      const { data } = await api.patch(
        `${PROVIDER_KEYS_REST_ENDPOINT}${providerKey.id}`,
        {
          api_key: providerKey.apiKey,
          ...configuration,
        },
      );
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

export default useProviderKeysUpdateMutation;
