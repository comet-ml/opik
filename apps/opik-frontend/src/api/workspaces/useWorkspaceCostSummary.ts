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
      validateStatus: (status) => status === 200 || status === 404, // TODO lala delete this line when backend is ready
    },
  );

  // Simulate network delay for demo purposes
  await new Promise((resolve) =>
    setTimeout(resolve, Math.floor(Math.random() * (3000 - 200 + 1)) + 200),
  );

  return {
    current: Math.random() < 0.9 ? Math.random() * 100000000 : null,
    previous: Math.random() < 0.9 ? Math.random() * 100000000 : null,
  } as WorkspaceCostSummary;

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
