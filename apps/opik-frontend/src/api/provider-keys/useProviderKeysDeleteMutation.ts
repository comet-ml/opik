import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import { useToast } from "@/components/ui/use-toast";
import api, {
  PROVIDER_KEYS_REST_ENDPOINT,
  PROVIDERS_KEYS_KEY,
} from "@/api/api";

type UseProviderKeysDeleteMutationParams = {
  providerId: string;
};

const useProviderKeysDeleteMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ providerId }: UseProviderKeysDeleteMutationParams) => {
      const { data } = await api.post(`${PROVIDER_KEYS_REST_ENDPOINT}delete`, {
        ids: [providerId],
      });

      return data;
    },
    onError: (error) => {
      const message = get(
        error,
        ["response", "data", "message"],
        error.message,
      );

      toast({
        title: "Error",
        description: message,
        variant: "destructive",
      });
    },
    onSettled: () => {
      return queryClient.invalidateQueries({ queryKey: [PROVIDERS_KEYS_KEY] });
    },
  });
};

export default useProviderKeysDeleteMutation;
