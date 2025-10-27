import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import api, { DASHBOARDS_REST_ENDPOINT, DASHBOARD_KEY } from "@/api/api";
import { useToast } from "@/components/ui/use-toast";

type UseChartDeleteMutationParams = {
  dashboardId: string;
  chartId: string;
};

const useChartDeleteMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      dashboardId,
      chartId,
    }: UseChartDeleteMutationParams) => {
      const { data } = await api.delete(
        `${DASHBOARDS_REST_ENDPOINT}${dashboardId}/charts/${chartId}`
      );
      return data;
    },
    onSuccess: (data, variables) => {
      // Invalidate dashboard query to refetch without deleted chart
      queryClient.invalidateQueries({
        queryKey: [DASHBOARD_KEY, variables.dashboardId],
      });

      toast({
        description: "Chart deleted successfully",
      });
    },
    onError: (error: AxiosError) => {
      toast({
        description: error.message || "Failed to delete chart",
        variant: "destructive",
      });
    },
  });
};

export default useChartDeleteMutation;



