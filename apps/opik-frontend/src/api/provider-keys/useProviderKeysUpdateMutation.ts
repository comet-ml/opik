import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";

import api, {
  PROVIDER_KEYS_REST_ENDPOINT,
  PROVIDERS_KEYS_KEY,
} from "@/api/api";
import { useToast } from "@/components/ui/use-toast";
import { PartialProviderKeyUpdate } from "@/types/providers";

type UseProviderKeyUpdateMutationParams = {
  providerKey: PartialProviderKeyUpdate;
};

const useProviderKeysUpdateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ providerKey }: UseProviderKeyUpdateMutationParams) => {
      const { data } = await api.patch(
        `${PROVIDER_KEYS_REST_ENDPOINT}${providerKey.id}`,
        {
          ...(providerKey.apiKey && { api_key: providerKey.apiKey }),
          ...(providerKey.base_url && { base_url: providerKey.base_url }),
          ...(providerKey?.configuration && {
            configuration: providerKey.configuration,
          }),
          ...(providerKey?.headers && { headers: providerKey.headers }),
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
