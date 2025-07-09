import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";

import api, { DASHBOARD_TEMPLATES_KEY, DASHBOARD_TEMPLATES_REST_ENDPOINT } from "@/api/api";
import { DashboardTemplate } from "./useDashboardTemplatesById";

type UpdateDashboardTemplateRequest = {
  dashboardTemplateId: string;
  name?: string;
  description?: string;
  configuration?: any;
};

export default function useDashboardTemplateUpdateMutation() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({ dashboardTemplateId, ...dashboardTemplate }: UpdateDashboardTemplateRequest): Promise<DashboardTemplate> => {
      const { data } = await api.put(`${DASHBOARD_TEMPLATES_REST_ENDPOINT}${dashboardTemplateId}`, dashboardTemplate);
      return data;
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

      console.error("Failed to update dashboard template", message);
    },
  });
} 