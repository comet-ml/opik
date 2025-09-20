import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  PROJECTS_KEY,
  PROJECTS_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import { Project } from "@/types/projects";
import { processSorting } from "@/lib/sorting";
import { Sorting } from "@/types/sorting";

type UseProjectsListParams = {
  workspaceName: string;
  search?: string;
  sorting?: Sorting;
  page: number;
  size: number;
};

type UseProjectsListResponse = {
  content: Project[];
  total: number;
};

const getProjectsList = async (
  { signal }: QueryFunctionContext,
  { search, sorting, size, page }: UseProjectsListParams,
) => {
  const sortingResult = processSorting(sorting);

  const requestParams = {
    ...sortingResult,
    ...(search && { name: search }),
    size,
    page,
  };

  const { data } = await api.get(PROJECTS_REST_ENDPOINT, {
    signal,
    params: requestParams,
  });

  return data;
};

export default function useProjectsList(
  params: UseProjectsListParams,
  options?: QueryConfig<UseProjectsListResponse>,
) {
  return useQuery({
    queryKey: [PROJECTS_KEY, params],
    queryFn: (context) => getProjectsList(context, params),
    ...options,
  });
}
