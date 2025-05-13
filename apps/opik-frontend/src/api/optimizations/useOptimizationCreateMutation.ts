import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import api, { OPTIMIZATIONS_KEY, OPTIMIZATIONS_REST_ENDPOINT } from "@/api/api";
import { Optimization } from "@/types/optimizations";
import { AxiosError } from "axios";
import { useToast } from "@/components/ui/use-toast";
import { extractIdFromLocation } from "@/lib/utils";

type UseProjectCreateMutationParams = {
  optimization: Partial<Optimization>;
};

const useOptimizationCreateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ optimization }: UseProjectCreateMutationParams) => {
      const { headers } = await api.post(OPTIMIZATIONS_REST_ENDPOINT, {
        ...optimization,
      });

      // TODO workaround to return just created resource while implementation on BE is not done
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
        queryKey: [OPTIMIZATIONS_KEY],
      });
    },
  });
};

export default useOptimizationCreateMutation;
