import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { PROJECTS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { Project } from "@/types/projects";
import useQueryErrorToast from "@/hooks/useQueryErrorToast";

const getProjectById = async (
  { signal }: QueryFunctionContext,
  { projectId }: UseProjectByIdParams,
) => {
  const { data } = await api.get(PROJECTS_REST_ENDPOINT + projectId, {
    signal,
  });

  return data;
};

type UseProjectByIdParams = {
  projectId: string;
};

export default function useProjectById(
  params: UseProjectByIdParams,
  options?: QueryConfig<Project>,
) {
  const query = useQuery({
    queryKey: ["project", params],
    queryFn: (context) => getProjectById(context, params),
    ...options,
  });

  useQueryErrorToast({ isError: query.isError, error: query.error });

  return query;
}
