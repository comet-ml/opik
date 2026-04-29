import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import { useToast } from "@/ui/use-toast";
import api, {
  INSIGHTS_VIEWS_KEY,
  INSIGHTS_VIEWS_REST_ENDPOINT,
} from "@/api/api";

type UseInsightsViewBatchDeleteMutationParams = {
  ids: string[];
};

const useInsightsViewBatchDeleteMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ ids }: UseInsightsViewBatchDeleteMutationParams) => {
      const { data } = await api.post(
        `${INSIGHTS_VIEWS_REST_ENDPOINT}delete-batch`,
        {
          ids,
        },
      );
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
      return queryClient.invalidateQueries({
        queryKey: [INSIGHTS_VIEWS_KEY],
      });
    },
  });
};

export default useInsightsViewBatchDeleteMutation;
