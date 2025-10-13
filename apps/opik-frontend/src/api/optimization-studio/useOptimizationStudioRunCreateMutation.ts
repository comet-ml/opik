import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import api, {
  OPTIMIZATION_STUDIO_REST_ENDPOINT,
  OPTIMIZATION_STUDIO_RUNS_KEY,
} from "@/api/api";
import { OptimizationStudioRunCreate } from "@/types/optimization-studio";
import { AxiosError } from "axios";
import { useToast } from "@/components/ui/use-toast";
import { extractIdFromLocation } from "@/lib/utils";

type UseOptimizationStudioRunCreateMutationParams = {
  run: OptimizationStudioRunCreate;
};

const useOptimizationStudioRunCreateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ run }: UseOptimizationStudioRunCreateMutationParams) => {
      const response = await api.post(OPTIMIZATION_STUDIO_REST_ENDPOINT, {
        ...run,
      });

      console.log("Full response:", response);
      console.log("Response headers:", response.headers);
      console.log("All header keys:", Object.keys(response.headers || {}));

      const location = response.headers?.location || response.headers?.Location;
      const id = extractIdFromLocation(location);

      console.log("Location header:", location, "Extracted ID:", id);

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
        queryKey: [OPTIMIZATION_STUDIO_RUNS_KEY],
      });
    },
  });
};

export default useOptimizationStudioRunCreateMutation;
