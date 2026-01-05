import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import api, {
  OPTIMIZATIONS_KEY,
  OPTIMIZATION_KEY,
  OPTIMIZATIONS_REST_ENDPOINT,
} from "@/api/api";
import { OptimizationUpdate } from "@/types/optimizations";
import { AxiosError } from "axios";
import { useToast } from "@/components/ui/use-toast";
import useAppStore from "@/store/AppStore";

type UseOptimizationUpdateMutationParams = {
  optimizationId: string;
  update: OptimizationUpdate;
};

type UseOptimizationUpdateMutationOptions = {
  successMessage?: string;
};

const useOptimizationUpdateMutation = (
  options: UseOptimizationUpdateMutationOptions = {},
) => {
  const { successMessage } = options;
  const queryClient = useQueryClient();
  const { toast } = useToast();
  const opikApiKey = useAppStore((state) => state.user.apiKey);

  return useMutation({
    mutationFn: async ({
      optimizationId,
      update,
    }: UseOptimizationUpdateMutationParams) => {
      await api.put(`${OPTIMIZATIONS_REST_ENDPOINT}${optimizationId}`, update, {
        headers: {
          ...(opikApiKey && { opikApiKey }),
        },
      });

      return { optimizationId };
    },
    onError: (error: AxiosError) => {
      const message =
        get(error, ["response", "data", "message"], error.message) ||
        "An unknown error occurred while updating the optimization. Please try again.";

      toast({
        title: "Error",
        description: message,
        variant: "destructive",
      });
    },
    onSuccess: () => {
      if (successMessage) {
        toast({
          title: "Success",
          description: successMessage,
        });
      }
    },
    onSettled: (_, __, variables) => {
      // Invalidate the specific optimization query
      queryClient.invalidateQueries({
        queryKey: [OPTIMIZATION_KEY, variables.optimizationId],
      });
      // Invalidate the optimizations list
      queryClient.invalidateQueries({
        queryKey: [OPTIMIZATIONS_KEY],
      });
    },
  });
};

export default useOptimizationUpdateMutation;
