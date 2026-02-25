import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import api, {
  OPTIMIZER_CONFIGS_KEY,
  OPTIMIZER_CONFIGS_REST_ENDPOINT,
} from "@/api/api";
import { OptimizerConfigEnvsRequest } from "@/types/optimizer-configs";
import { AxiosError } from "axios";
import { useToast } from "@/components/ui/use-toast";

type UseOptimizerConfigEnvsMutationParams = {
  envsRequest: OptimizerConfigEnvsRequest;
};

const useOptimizerConfigEnvsMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      envsRequest,
    }: UseOptimizerConfigEnvsMutationParams) => {
      const { data } = await api.post(
        `${OPTIMIZER_CONFIGS_REST_ENDPOINT}envs/`,
        envsRequest,
      );

      return data;
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
        queryKey: [OPTIMIZER_CONFIGS_KEY, 'envs'],
      });
    },
  });
};

export default useOptimizerConfigEnvsMutation;
