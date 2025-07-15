import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig, WORKSPACES_REST_ENDPOINT } from "@/api/api";
import { WorkspaceMetric } from "@/types/workspaces";

type UseWorkspaceMetricsParams = {
  projectIds: string[];
  name: string;
  intervalStart: string;
  intervalEnd: string;
};

interface WorkspaceMetricsResponse {
  results: WorkspaceMetric[];
}

const getWorkspaceMetrics = async (
  { signal }: QueryFunctionContext,
  { projectIds, intervalStart, intervalEnd, name }: UseWorkspaceMetricsParams,
) => {
  const { data } = await api.post<WorkspaceMetricsResponse>(
    `${WORKSPACES_REST_ENDPOINT}metrics`,
    {
      ...(projectIds.length > 0 && { project_ids: projectIds }),
      name,
      interval_start: intervalStart,
      interval_end: intervalEnd,
    },
    {
      signal,
    },
  );

  return data?.results;
};

const useWorkspaceMetrics = (
  params: UseWorkspaceMetricsParams,
  config?: QueryConfig<WorkspaceMetric[]>,
) => {
  return useQuery({
    queryKey: ["workspace-metrics", params],
    queryFn: (context) => getWorkspaceMetrics(context, params),
    ...config,
  });
};

export default useWorkspaceMetrics;
