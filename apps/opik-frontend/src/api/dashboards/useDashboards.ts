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

export interface DashboardPage {
  content: Dashboard[];
  page: number;
  size: number;
  total: number;
}

type UseDashboardsParams = {
  page?: number;
  size?: number;
};

const getDashboards = async (
  { signal }: QueryFunctionContext,
  { page = 1, size = 100 }: UseDashboardsParams,
) => {
  const { data } = await api.get<DashboardPage>(DASHBOARDS_REST_ENDPOINT, {
    params: { page, size },
    signal,
  });

  return data;
};

export const useDashboards = (
  params: UseDashboardsParams,
  options?: QueryConfig<DashboardPage>,
) => {
  return useQuery({
    queryKey: [DASHBOARDS_KEY, params],
    queryFn: (context) => getDashboards(context, params),
    ...options,
  });
}; 