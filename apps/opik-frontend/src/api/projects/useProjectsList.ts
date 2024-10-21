import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { PROJECTS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { Project } from "@/types/projects";

type UseProjectsListParams = {
  workspaceName: string;
  search?: string;
  page: number;
  size: number;
};

type UseProjectsListResponse = {
  content: Project[];
  total: number;
};

const getProjectsList = async (
  { signal }: QueryFunctionContext,
  { workspaceName, search, size, page }: UseProjectsListParams,
) => {
  const { data } = await api.get(PROJECTS_REST_ENDPOINT, {
    signal,
    params: {
      workspace_name: workspaceName,
      ...(search && { name: search }),
      size,
      page,
    },
  });

  return data;
};

export default function useProjectsList(
  params: UseProjectsListParams,
  options?: QueryConfig<UseProjectsListResponse>,
) {
  return useQuery({
    queryKey: ["projects", params],
    queryFn: (context) => getProjectsList(context, params),
    ...options,
  });
}
