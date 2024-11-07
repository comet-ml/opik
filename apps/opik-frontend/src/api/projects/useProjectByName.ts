import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { PROJECTS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { Project } from "@/types/projects";

type UseProjectByNameParams = {
  projectName: string;
};

type UseProjectsListResponse = {
  content: Project[];
  total: number;
};

// @todo: replace it with another request to get a project id only when the be is ready
const getProjectByName = async (
  { signal }: QueryFunctionContext,
  { projectName }: UseProjectByNameParams,
) => {
  const { data } = await api.get<UseProjectsListResponse>(
    PROJECTS_REST_ENDPOINT,
    {
      signal,
      params: {
        search: projectName,
        page: 1,
        size: 10,
      },
    },
  );

  return data?.content.find((p) => p.name === projectName) || null;
};

export default function useProjectByName(
  params: UseProjectByNameParams,
  options?: QueryConfig<Project | null>,
) {
  return useQuery({
    queryKey: ["project", params],
    queryFn: (context) => getProjectByName(context, params),
    ...options,
  });
}
