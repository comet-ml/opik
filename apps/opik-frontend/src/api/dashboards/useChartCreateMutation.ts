import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import api, { DASHBOARDS_REST_ENDPOINT, DASHBOARD_KEY } from "@/api/api";
import { DashboardChart } from "@/types/dashboards";
import { useToast } from "@/components/ui/use-toast";

type UseChartCreateMutationParams = {
  dashboardId: string;
  chart: Partial<DashboardChart>;
};

const useChartCreateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ dashboardId, chart }: UseChartCreateMutationParams) => {
      const { data } = await api.post(
        `${DASHBOARDS_REST_ENDPOINT}${dashboardId}/charts`,
        chart
      );
      return data;
    },
    onSuccess: (data, variables) => {
      // Invalidate dashboard query to refetch with new chart
      queryClient.invalidateQueries({
        queryKey: [DASHBOARD_KEY, variables.dashboardId],
      });

      toast({
        description: "Chart created successfully",
      });
    },
    onError: (error: AxiosError) => {
      toast({
        description: error.message || "Failed to create chart",
        variant: "destructive",
      });
    },
  });
};

export default useChartCreateMutation;



