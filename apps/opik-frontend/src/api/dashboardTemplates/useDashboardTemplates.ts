import { useQuery } from "@tanstack/react-query";
import api, { DASHBOARD_TEMPLATES_KEY, DASHBOARD_TEMPLATES_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { DashboardTemplate } from "./useDashboardTemplatesById";

type UseDashboardTemplatesParams = {
  search?: string;
};

export default function useDashboardTemplates(
  params: UseDashboardTemplatesParams = {},
  options?: QueryConfig<DashboardTemplate[]>
) {
  return useQuery({
    queryKey: [DASHBOARD_TEMPLATES_KEY, params],
    queryFn: async () => {
      const { data } = await api.get(DASHBOARD_TEMPLATES_REST_ENDPOINT);
      
      // Apply client-side filtering if search is provided
      if (params.search) {
        const searchLower = params.search.toLowerCase();
        return data.filter((template: DashboardTemplate) =>
          template.name.toLowerCase().includes(searchLower) ||
          (template.description && template.description.toLowerCase().includes(searchLower))
        );
      }
      
      return data;
    },
    ...options,
  });
} 