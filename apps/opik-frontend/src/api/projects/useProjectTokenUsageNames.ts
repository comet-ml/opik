import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { PROJECTS_REST_ENDPOINT, QueryConfig } from "@/api/api";

type TokenUsageNamesResponse = {
  names: string[];
};

const getProjectTokenUsageNames = async (
  { signal }: QueryFunctionContext,
  { projectId }: UseProjectTokenUsageNamesParams,
) => {
  const { data } = await api.get<TokenUsageNamesResponse>(
    `${PROJECTS_REST_ENDPOINT}${projectId}/token-usage/names`,
    {
      signal,
    },
  );

  return data;
};

type UseProjectTokenUsageNamesParams = {
  projectId: string;
};

export default function useProjectTokenUsageNames(
  params: UseProjectTokenUsageNamesParams,
  options?: QueryConfig<TokenUsageNamesResponse>,
) {
  return useQuery({
    queryKey: ["projectTokenUsageNames", params],
    queryFn: (context) => getProjectTokenUsageNames(context, params),
    ...options,
  });
}
