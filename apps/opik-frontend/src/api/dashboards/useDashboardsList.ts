import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  DASHBOARDS_KEY,
  DASHBOARDS_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import { Dashboard } from "@/types/dashboards";
import { processSorting } from "@/lib/sorting";
import { Sorting } from "@/types/sorting";

type UseDashboardsListParams = {
  workspaceName: string;
  projectId?: string;
  page?: number;
  size?: number;
  name?: string;
  type?: string;
  sorting?: Sorting;
};

type UseDashboardsListResponse = {
  content: Dashboard[];
  total: number;
  page: number;
  size: number;
};

const getDashboardsList = async (
  { signal }: QueryFunctionContext,
  { workspaceName, projectId, page = 1, size = 10, name, type, sorting }: UseDashboardsListParams,
) => {
  const { data } = await api.get(DASHBOARDS_REST_ENDPOINT, {
    signal,
    params: {
      workspace_name: workspaceName,
      ...processSorting(sorting),
      ...(projectId && { project_id: projectId }),
      ...(name && { name }),
      ...(type && { type }),
      page,
      size,
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



