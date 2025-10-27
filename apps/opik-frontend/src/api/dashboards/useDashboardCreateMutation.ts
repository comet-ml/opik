import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import api, { DASHBOARDS_KEY, DASHBOARDS_REST_ENDPOINT } from "@/api/api";
import { Dashboard } from "@/types/dashboards";

type UseDashboardCreateMutationParams = {
  dashboard: Partial<Dashboard>;
  workspaceName: string;
};

const useDashboardCreateMutation = () => {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({
      dashboard,
      workspaceName,
    }: UseDashboardCreateMutationParams) => {
      const { data } = await api.post(DASHBOARDS_REST_ENDPOINT, dashboard, {
        params: {
          workspace_name: workspaceName,
        },
      });
      return data;
    },
    onSettled: () => {
      return queryClient.invalidateQueries({ queryKey: [DASHBOARDS_KEY] });
    },
    onError: (error: AxiosError) => {
      console.error("Error creating dashboard:", error);
    },
  });
};

export default useDashboardCreateMutation;



