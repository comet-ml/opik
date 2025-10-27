import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import api, { DASHBOARDS_KEY, DASHBOARDS_REST_ENDPOINT } from "@/api/api";

type UseDashboardDeleteMutationParams = {
  dashboardId: string;
  workspaceName: string;
};

const useDashboardDeleteMutation = () => {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({
      dashboardId,
      workspaceName,
    }: UseDashboardDeleteMutationParams) => {
      const { data } = await api.delete(
        `${DASHBOARDS_REST_ENDPOINT}${dashboardId}`,
        {
          params: {
            workspace_name: workspaceName,
          },
        },
      );
      return data;
    },
    onSettled: () => {
      return queryClient.invalidateQueries({ queryKey: [DASHBOARDS_KEY] });
    },
    onError: (error: AxiosError) => {
      console.error("Error deleting dashboard:", error);
    },
  });
};

export default useDashboardDeleteMutation;



