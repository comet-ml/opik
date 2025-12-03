import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import { useToast } from "@/components/ui/use-toast";
import api, { DASHBOARDS_KEY, DASHBOARDS_REST_ENDPOINT } from "@/api/api";

type UseDashboardBatchDeleteMutationParams = {
  ids: string[];
};

const useDashboardBatchDeleteMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ ids }: UseDashboardBatchDeleteMutationParams) => {
      const { data } = await api.post(
        `${DASHBOARDS_REST_ENDPOINT}delete-batch`,
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
        queryKey: [DASHBOARDS_KEY],
      });
    },
  });
};

export default useDashboardBatchDeleteMutation;
