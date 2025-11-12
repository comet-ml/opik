import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig, WORKSPACES_REST_ENDPOINT } from "@/api/api";
import { WorkspaceCost } from "@/types/workspaces";

type UseWorkspaceCostParams = {
  projectIds: string[];
  intervalStart: string | undefined;
  intervalEnd: string | undefined;
};

interface WorkspaceCostResponse {
  results: WorkspaceCost[];
}

const getWorkspaceCost = async (
  { signal }: QueryFunctionContext,
  { projectIds, intervalStart, intervalEnd }: UseWorkspaceCostParams,
) => {
  const { data } = await api.post<WorkspaceCostResponse>(
    `${WORKSPACES_REST_ENDPOINT}costs`,
    {
      ...(projectIds.length > 0 && { project_ids: projectIds }),
      ...(intervalStart && { interval_start: intervalStart }),
      ...(intervalEnd && { interval_end: intervalEnd }),
    },
    {
      signal,
    },
  );

  return data?.results;
};

const useWorkspaceCost = (
  params: UseWorkspaceCostParams,
  config?: QueryConfig<WorkspaceCost[]>,
) => {
  return useQuery({
    queryKey: ["workspace-costs", params],
    queryFn: (context) => getWorkspaceCost(context, params),
    ...config,
  });
};

export default useWorkspaceCost;
