import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  DASHBOARD_KEY,
  DASHBOARDS_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import { Dashboard } from "@/types/dashboard";

type UseDashboardByIdParams = {
  dashboardId: string;
};

const getDashboardById = async (
  { signal }: QueryFunctionContext,
  { dashboardId }: UseDashboardByIdParams,
) => {
  const { data } = await api.get(`${DASHBOARDS_REST_ENDPOINT}${dashboardId}`, {
    signal,
  });

  return data;
};

export default function useDashboardById(
  params: UseDashboardByIdParams,
  options?: QueryConfig<Dashboard>,
) {
  return useQuery({
    queryKey: [DASHBOARD_KEY, params],
    queryFn: (context) => getDashboardById(context, params),
    ...options,
  });
}
