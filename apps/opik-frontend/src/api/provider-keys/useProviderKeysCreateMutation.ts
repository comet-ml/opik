import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import api, {
  PROVIDER_KEYS_REST_ENDPOINT,
  PROVIDERS_KEYS_KEY,
} from "@/api/api";
import { AxiosError } from "axios";
import { useToast } from "@/components/ui/use-toast";
import { ProviderKeyWithAPIKey } from "@/types/providers";

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
      const configuration = providerKey?.location
        ? { configuration: { location: providerKey.location } }
        : {};

      const { data } = await api.post(PROVIDER_KEYS_REST_ENDPOINT, {
        provider: providerKey.provider,
        api_key: providerKey.apiKey,
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
