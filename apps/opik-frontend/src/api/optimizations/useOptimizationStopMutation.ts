import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import api, {
  OPTIMIZATIONS_KEY,
  OPTIMIZATIONS_REST_ENDPOINT,
} from "@/api/api";
import { OPTIMIZATION_STATUS } from "@/types/optimizations";
import { AxiosError } from "axios";
import { useToast } from "@/components/ui/use-toast";
import useAppStore from "@/store/AppStore";

type UseOptimizationStopMutationParams = {
  optimizationId: string;
};

const useOptimizationStopMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();
  const opikApiKey = useAppStore((state) => state.user.apiKey);

  return useMutation({
    mutationFn: async ({ optimizationId }: UseOptimizationStopMutationParams) =>
      api.put(
        `${OPTIMIZATIONS_REST_ENDPOINT}${optimizationId}`,
        { status: OPTIMIZATION_STATUS.CANCELLED },
        {
          headers: {
            ...(opikApiKey && { opikApiKey }),
          },
        },
      ),
    onError: (error: AxiosError) => {
      const message =
        get(error, ["response", "data", "message"], error.message) ||
        "Failed to stop the optimization. Please try again.";

      toast({
        title: "Error",
        description: message,
        variant: "destructive",
      });
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({
        queryKey: [OPTIMIZATIONS_KEY],
      });

      toast({
        description: "Optimization stopped successfully",
      });
    },
  });
};

export default useOptimizationStopMutation;

