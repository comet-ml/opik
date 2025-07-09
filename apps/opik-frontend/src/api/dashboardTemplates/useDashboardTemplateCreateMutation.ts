import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";

import api, { DASHBOARD_TEMPLATES_KEY, DASHBOARD_TEMPLATES_REST_ENDPOINT } from "@/api/api";
import { DashboardTemplate } from "./useDashboardTemplatesById";

type CreateDashboardTemplateRequest = {
  name: string;
  description?: string;
  configuration: any;
};

export default function useDashboardTemplateCreateMutation() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (dashboardTemplate: CreateDashboardTemplateRequest): Promise<DashboardTemplate> => {
      const { data } = await api.post(DASHBOARD_TEMPLATES_REST_ENDPOINT, dashboardTemplate);
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

      console.error("Failed to create dashboard template", message);
    },
  });
} 