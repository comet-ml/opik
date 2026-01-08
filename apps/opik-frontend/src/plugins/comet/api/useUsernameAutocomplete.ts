import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig } from "../api";

interface UseUsernameAutocompleteParams {
  query: string;
  organizationId: string;
  excludedWorkspaceId?: string;
}

const getUsernameAutocomplete = async (
  context: QueryFunctionContext,
  params: UseUsernameAutocompleteParams,
): Promise<string[]> => {
  const { query, organizationId, excludedWorkspaceId } = params;
  const response = await api.get<string[]>("/autocomplete/username", {
    params: {
      query,
      organizationId,
      excludedWorkspaceId,
    },
    signal: context.signal,
  });

  return response?.data || [];
};

const useUsernameAutocomplete = (
  params: UseUsernameAutocompleteParams,
  options?: QueryConfig<string[]>,
) => {
  return useQuery({
    queryKey: [
      "username-autocomplete",
      {
        query: params.query,
        organizationId: params.organizationId,
        excludedWorkspaceId: params.excludedWorkspaceId,
      },
    ],
    queryFn: (context) => getUsernameAutocomplete(context, params),
    enabled: Boolean(params.query && params.organizationId),
    ...options,
  });
};

export default useUsernameAutocomplete;
