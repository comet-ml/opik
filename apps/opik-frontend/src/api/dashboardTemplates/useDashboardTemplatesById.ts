import { useQuery } from "@tanstack/react-query";
import api, { DASHBOARD_TEMPLATES_KEY, DASHBOARD_TEMPLATES_REST_ENDPOINT, QueryConfig } from "@/api/api";

export type DashboardTemplate = {
  id: string;
  name: string;
  description?: string;
  configuration: {
    sections: {
      id: string;
      title: string;
      position_order: number;
      panels: {
        id: string;
        name: string;
        type: string;
        configuration: any;
        layout: any;
        template_id?: string;
      }[];
    }[];
  };
  workspace_id: string;
  created_at: string;
  created_by: string;
  last_updated_at: string;
  last_updated_by: string;
};

type UseDashboardTemplateByIdParams = {
  dashboardTemplateId: string;
};

export default function useDashboardTemplatesById(
  params: UseDashboardTemplateByIdParams,
  options?: QueryConfig<DashboardTemplate>
) {
  return useQuery({
    queryKey: [DASHBOARD_TEMPLATES_KEY, params],
    queryFn: async () => {
      const { data } = await api.get(`${DASHBOARD_TEMPLATES_REST_ENDPOINT}${params.dashboardTemplateId}`);
      return data;
    },
    ...options,
  });
} 