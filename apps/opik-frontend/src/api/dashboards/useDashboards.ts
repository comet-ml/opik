import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { DASHBOARDS_REST_ENDPOINT, DASHBOARDS_KEY, QueryConfig } from "@/api/api";

export interface Dashboard {
  id: string;
  name: string;
  description?: string;
  created_at: string;
  created_by: string;
  last_updated_at: string;
  last_updated_by: string;
}

type UseDashboardsParams = {};

const getDashboards = async (
  { signal }: QueryFunctionContext,
  {}: UseDashboardsParams,
) => {
  const { data } = await api.get<Dashboard[]>(DASHBOARDS_REST_ENDPOINT, {
    signal,
  });

  return data;
};

export default function useDashboards(
  params: UseDashboardsParams,
  options?: QueryConfig<Dashboard[]>,
) {
  return useQuery({
    queryKey: [DASHBOARDS_KEY, params],
    queryFn: (context) => getDashboards(context, params),
    ...options,
  });
} 
