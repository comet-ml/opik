import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";

import api, {
  PROVIDER_KEYS_REST_ENDPOINT,
  PROVIDERS_KEYS_KEY,
} from "@/api/api";
import { useToast } from "@/ui/use-toast";
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
          // "" clears the stored key when switching to token auth, so
          // the check is on undefined, not truthiness
          ...(providerKey.apiKey !== undefined && {
            api_key: providerKey.apiKey,
          }),
          ...(providerKey.base_url && { base_url: providerKey.base_url }),
          ...(providerKey?.configuration && {
            configuration: providerKey.configuration,
          }),
          ...(providerKey?.headers && { headers: providerKey.headers }),
          // {} clears the stored recipe, so the check is on undefined, not truthiness
          ...(providerKey.auth_config !== undefined && {
            auth_config: providerKey.auth_config,
          }),
        },
      );
      return data;
    },
    onError: (error: AxiosError) => {
      // the backend 400s in two shapes: bean validation -> {errors: [...]},
      // service BadRequestException -> {message: "..."} (Dropwizard ErrorMessage)
      const message =
        get(error, ["response", "data", "message"]) ??
        get(error, ["response", "data", "errors", "0"], error.message);

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
