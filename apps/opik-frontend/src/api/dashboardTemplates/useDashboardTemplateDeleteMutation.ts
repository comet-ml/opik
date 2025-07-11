import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";

import api, { DASHBOARD_TEMPLATES_KEY, DASHBOARD_TEMPLATES_REST_ENDPOINT } from "@/api/api";

type DeleteDashboardTemplateRequest = {
  dashboardTemplateId: string;
};

export default function useDashboardTemplateDeleteMutation() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({ dashboardTemplateId }: DeleteDashboardTemplateRequest): Promise<void> => {
      await api.delete(`${DASHBOARD_TEMPLATES_REST_ENDPOINT}${dashboardTemplateId}`);
    },
    onSuccess: () => {
      return queryClient.invalidateQueries({
        queryKey: [DASHBOARD_TEMPLATES_KEY],
      });
    },
    onError: (error: AxiosError) => {
      const message = get(
        error,
        ["response", "data", "message"],
        error.message
      );

      console.error("Failed to delete dashboard template", message);
    },
  });
} 