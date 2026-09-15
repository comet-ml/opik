import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { WORKSPACES_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { TokenUsageNamesResponse } from "@/api/projects/useProjectTokenUsageNames";

type UseWorkspaceTokenUsageNamesParams = {
  projectIds: string[];
};

const getWorkspaceTokenUsageNames = async (
  { signal }: QueryFunctionContext,
  { projectIds }: UseWorkspaceTokenUsageNamesParams,
) => {
  const { data } = await api.post<TokenUsageNamesResponse>(
    `${WORKSPACES_REST_ENDPOINT}token-usage/names`,
    {
      // Empty => all projects in the workspace; otherwise only the selected set
      ...(projectIds.length > 0 && { project_ids: projectIds }),
    },
    {
      signal,
    },
  );

  return data;
};

export default function useWorkspaceTokenUsageNames(
  params: UseWorkspaceTokenUsageNamesParams,
  options?: QueryConfig<TokenUsageNamesResponse>,
) {
  return useQuery({
    queryKey: ["workspaceTokenUsageNames", params],
    queryFn: (context) => getWorkspaceTokenUsageNames(context, params),
    ...options,
  });
}
