import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import api, { DASHBOARDS_REST_ENDPOINT, DASHBOARD_KEY } from "@/api/api";
import { DashboardChart } from "@/types/dashboards";
import { useToast } from "@/components/ui/use-toast";

type UseChartUpdateMutationParams = {
  dashboardId: string;
  chartId: string;
  chart: Partial<DashboardChart>;
};

const useChartUpdateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      dashboardId,
      chartId,
      chart,
    }: UseChartUpdateMutationParams) => {
      const { data } = await api.patch(
        `${DASHBOARDS_REST_ENDPOINT}${dashboardId}/charts/${chartId}`,
        chart
      );
      return data;
    },
    onSuccess: (data, variables) => {
      // Invalidate dashboard query to refetch with updated chart
      queryClient.invalidateQueries({
        queryKey: [DASHBOARD_KEY, variables.dashboardId],
      });

      toast({
        description: "Chart updated successfully",
      });
    },
    onError: (error: AxiosError) => {
      toast({
        description: error.message || "Failed to update chart",
        variant: "destructive",
      });
    },
  });
};

export default useChartUpdateMutation;



