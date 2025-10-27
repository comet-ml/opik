import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import api, {
  DASHBOARDS_KEY,
  DASHBOARD_KEY,
  DASHBOARDS_REST_ENDPOINT,
} from "@/api/api";
import { Dashboard } from "@/types/dashboards";

type UseDashboardUpdateMutationParams = {
  dashboardId: string;
  dashboard: Partial<Dashboard>;
  workspaceName: string;
};

const useDashboardUpdateMutation = () => {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({
      dashboardId,
      dashboard,
      workspaceName,
    }: UseDashboardUpdateMutationParams) => {
      const { data } = await api.patch(
        `${DASHBOARDS_REST_ENDPOINT}${dashboardId}`,
        dashboard,
        {
          params: {
            workspace_name: workspaceName,
          },
        },
      );
      return data;
    },
    onSettled: (_, __, { dashboardId }) => {
      queryClient.invalidateQueries({ queryKey: [DASHBOARDS_KEY] });
      queryClient.invalidateQueries({ queryKey: [DASHBOARD_KEY, { dashboardId }] });
    },
    onError: (error: AxiosError) => {
      console.error("Error updating dashboard:", error);
    },
  });
};

export default useDashboardUpdateMutation;



