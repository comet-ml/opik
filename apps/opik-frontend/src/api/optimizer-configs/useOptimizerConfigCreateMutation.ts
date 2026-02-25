import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import api, {
  OPTIMIZER_CONFIGS_KEY,
  OPTIMIZER_CONFIGS_REST_ENDPOINT,
} from "@/api/api";
import { OptimizerConfig } from "@/types/optimizer-configs";
import { AxiosError } from "axios";
import { useToast } from "@/components/ui/use-toast";
import { extractIdFromLocation } from "@/lib/utils";

type UseOptimizerConfigCreateMutationParams = {
  optimizerConfig: OptimizerConfig;
};

const useOptimizerConfigCreateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      optimizerConfig,
    }: UseOptimizerConfigCreateMutationParams) => {
      const { headers } = await api.post(
        OPTIMIZER_CONFIGS_REST_ENDPOINT,
        optimizerConfig,
      );

      const id = extractIdFromLocation(headers?.location);

      return { id };
    },
    onError: (error: AxiosError) => {
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
      queryClient.invalidateQueries({
        queryKey: [OPTIMIZER_CONFIGS_KEY],
      });
    },
  });
};

export default useOptimizerConfigCreateMutation;
