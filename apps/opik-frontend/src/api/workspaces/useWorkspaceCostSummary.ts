import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig, WORKSPACES_REST_ENDPOINT } from "@/api/api";
import { WorkspaceCostSummary } from "@/types/workspaces";

type UseWorkspaceCostSummaryParams = {
  projectIds: string[];
  intervalStart: string;
  intervalEnd: string;
};

interface WorkspaceCostSummaryResponse extends WorkspaceCostSummary {}

const getWorkspaceCostSummary = async (
  { signal }: QueryFunctionContext,
  { projectIds, intervalStart, intervalEnd }: UseWorkspaceCostSummaryParams,
) => {
  const { data } = await api.post<WorkspaceCostSummaryResponse>(
    `${WORKSPACES_REST_ENDPOINT}costs/summaries`,
    {
      ...(projectIds.length > 0 && { project_ids: projectIds }),
      interval_start: intervalStart,
      interval_end: intervalEnd,
    },
    {
      signal,
    },
  );

  return data;
};

const useWorkspaceCostSummary = (
  params: UseWorkspaceCostSummaryParams,
  config?: QueryConfig<WorkspaceCostSummary>,
) => {
  return useQuery({
    queryKey: ["workspace-costs-summary", params],
    queryFn: (context) => getWorkspaceCostSummary(context, params),
    ...config,
  });
};

export default useWorkspaceCostSummary;
