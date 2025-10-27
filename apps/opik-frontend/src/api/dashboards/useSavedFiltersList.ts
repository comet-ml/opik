import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  SAVED_FILTERS_KEY,
  PROJECTS_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import { SavedFilter } from "@/types/dashboards";

type UseSavedFiltersListParams = {
  workspaceName: string;
  projectId: string;
  page?: number;
  size?: number;
  name?: string;
};

type UseSavedFiltersListResponse = {
  content: SavedFilter[];
  total: number;
  page: number;
  size: number;
};

const getSavedFiltersList = async (
  { signal }: QueryFunctionContext,
  { workspaceName, projectId, page = 1, size = 10, name }: UseSavedFiltersListParams,
) => {
  const { data } = await api.get(
    `${PROJECTS_REST_ENDPOINT}${projectId}/filters`,
    {
      signal,
      params: {
        workspace_name: workspaceName,
        ...(name && { name }),
        page,
        size,
      },
    },
  );

  return data;
};

export default function useSavedFiltersList(
  params: UseSavedFiltersListParams,
  options?: QueryConfig<UseSavedFiltersListResponse>,
) {
  return useQuery({
    queryKey: [SAVED_FILTERS_KEY, params],
    queryFn: (context) => getSavedFiltersList(context, params),
    ...options,
  });
}



