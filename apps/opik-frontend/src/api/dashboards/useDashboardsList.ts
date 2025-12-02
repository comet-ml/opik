import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  DASHBOARDS_KEY,
  DASHBOARDS_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import { Dashboard } from "@/types/dashboard";

type UseDashboardsListParams = {
  workspaceName: string;
  search?: string;
  page: number;
  size: number;
};

type UseDashboardsListResponse = {
  content: Dashboard[];
  total: number;
};

const getDashboardsList = async (
  { signal }: QueryFunctionContext,
  { workspaceName, search, size, page }: UseDashboardsListParams,
) => {
  const { data } = await api.get(DASHBOARDS_REST_ENDPOINT, {
    signal,
    params: {
      workspace_name: workspaceName,
      ...(search && { name: search }),
      size,
      page,
    },
  });

  return data;
};

export default function useDashboardsList(
  params: UseDashboardsListParams,
  options?: QueryConfig<UseDashboardsListResponse>,
) {
  return useQuery({
    queryKey: [DASHBOARDS_KEY, params],
    queryFn: (context) => getDashboardsList(context, params),
    ...options,
  });
}
