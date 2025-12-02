import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import { useToast } from "@/components/ui/use-toast";
import api, { DASHBOARDS_KEY, DASHBOARDS_REST_ENDPOINT } from "@/api/api";

type UseDashboardDeleteMutationParams = {
  dashboardId: string;
};

const useDashboardDeleteMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ dashboardId }: UseDashboardDeleteMutationParams) => {
      const { data } = await api.delete(DASHBOARDS_REST_ENDPOINT + dashboardId);
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
        queryKey: [DASHBOARDS_KEY],
      });
    },
  });
};

export default useDashboardDeleteMutation;
