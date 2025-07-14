import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";
import api, { DASHBOARDS_REST_ENDPOINT, DASHBOARDS_KEY } from "@/api/api";
import { useToast } from "@/components/ui/use-toast";
import { DashboardSection } from "./useDashboardById";

export interface UpdateDashboardRequest {
  name?: string;
  description?: string;
  sections?: DashboardSection[];
}

export interface UpdateDashboardResponse {
  id: string;
  name: string;
  description?: string;
  created_at: string;
  created_by: string;
  last_updated_at: string;
  last_updated_by: string;
}

type UseDashboardUpdateMutationParams = {
  dashboardId: string;
  dashboard: UpdateDashboardRequest;
};

const useDashboardUpdateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ dashboardId, dashboard }: UseDashboardUpdateMutationParams) => {
      const { data } = await api.put<UpdateDashboardResponse>(
        `${DASHBOARDS_REST_ENDPOINT}${dashboardId}`,
        dashboard
      );

      return data;
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
    onSettled: (_, __, { dashboardId }) => {
      // Invalidate the general dashboards list
      queryClient.invalidateQueries({
        queryKey: [DASHBOARDS_KEY],
      });
      
      // Also invalidate the specific dashboard-by-id query
      queryClient.invalidateQueries({
        queryKey: [DASHBOARDS_KEY, { dashboardId }],
      });
    },
  });
};

export default useDashboardUpdateMutation; 
