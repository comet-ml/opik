import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import api, {
  ENVIRONMENT_KEY,
  ENVIRONMENTS_KEY,
  ENVIRONMENTS_REST_ENDPOINT,
} from "@/api/api";
import { useToast } from "@/ui/use-toast";

type UseEnvironmentBatchDeleteMutationParams = {
  ids: string[];
};

const useEnvironmentBatchDeleteMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ ids }: UseEnvironmentBatchDeleteMutationParams) => {
      const { data } = await api.post(`${ENVIRONMENTS_REST_ENDPOINT}delete`, {
        ids,
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
      queryClient.invalidateQueries({
        queryKey: [ENVIRONMENTS_KEY],
      });
      queryClient.removeQueries({
        queryKey: [ENVIRONMENT_KEY],
      });
    },
  });
};

export default useEnvironmentBatchDeleteMutation;
