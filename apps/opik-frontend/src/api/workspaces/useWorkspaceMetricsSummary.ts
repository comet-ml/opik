import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig, WORKSPACES_REST_ENDPOINT } from "@/api/api";
import { WorkspaceMetricSummary } from "@/types/workspaces";

type UseWorkspaceMetricsSummaryParams = {
  projectIds: string[];
  intervalStart: string;
  intervalEnd: string;
};

interface WorkspaceMetricsSummaryResponse {
  results: WorkspaceMetricSummary[];
}

const getWorkspaceMetricsSummary = async (
  { signal }: QueryFunctionContext,
  { projectIds, intervalStart, intervalEnd }: UseWorkspaceMetricsSummaryParams,
) => {
  const { data } = await api.post<WorkspaceMetricsSummaryResponse>(
    `${WORKSPACES_REST_ENDPOINT}metrics/summaries`,
    {
      ...(projectIds.length > 0 && { project_ids: projectIds }),
      interval_start: intervalStart,
      interval_end: intervalEnd,
    },
    {
      signal,
    },
  );

  return data?.results;
};

const useWorkspaceMetricsSummary = (
  params: UseWorkspaceMetricsSummaryParams,
  config?: QueryConfig<WorkspaceMetricSummary[]>,
) => {
  return useQuery({
    queryKey: ["workspace-metrics-summary", params],
    queryFn: (context) => getWorkspaceMetricsSummary(context, params),
    ...config,
  });
};

export default useWorkspaceMetricsSummary;
