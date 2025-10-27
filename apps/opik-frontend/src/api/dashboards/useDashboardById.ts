import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  DASHBOARD_KEY,
  DASHBOARDS_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import { Dashboard } from "@/types/dashboards";

type UseDashboardByIdParams = {
  dashboardId: string;
  workspaceName: string;
};

const getDashboard = async (
  { signal }: QueryFunctionContext,
  { dashboardId, workspaceName }: UseDashboardByIdParams,
) => {
  const { data } = await api.get(
    `${DASHBOARDS_REST_ENDPOINT}${dashboardId}`,
    {
      signal,
      params: {
        workspace_name: workspaceName,
      },
    },
  );

  return data;
};

export default function useDashboardById(
  params: UseDashboardByIdParams,
  options?: QueryConfig<Dashboard>,
) {
  return useQuery({
    queryKey: [DASHBOARD_KEY, params],
    queryFn: (context) => getDashboard(context, params),
    ...options,
  });
}



