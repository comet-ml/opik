import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";
import api, { DASHBOARDS_REST_ENDPOINT, DASHBOARDS_KEY } from "@/api/api";
import { useToast } from "@/components/ui/use-toast";

type UseDashboardDeleteMutationParams = {
  dashboardId: string;
};

const useDashboardDeleteMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ dashboardId }: UseDashboardDeleteMutationParams) => {
      await api.delete(`${DASHBOARDS_REST_ENDPOINT}${dashboardId}`);
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
        queryKey: [DASHBOARDS_KEY],
      });
    },
  });
};

export default useDashboardDeleteMutation; 
