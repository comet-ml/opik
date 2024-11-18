import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { PROJECTS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { Project } from "@/types/projects";

type UseProjectByNameParams = {
  projectName: string;
};

const getProjectByName = async (
  { signal }: QueryFunctionContext,
  { projectName }: UseProjectByNameParams,
) => {
  const { data } = await api.post<Project>(
    `${PROJECTS_REST_ENDPOINT}retrieve`,
    {
      name: projectName,
    },
    {
      signal,
    },
  );

  return data;
};

export default function useProjectByName(
  params: UseProjectByNameParams,
  options?: QueryConfig<Project>,
) {
  return useQuery({
    queryKey: ["project", params],
    queryFn: (context) => getProjectByName(context, params),
    ...options,
  });
}
