import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { PROJECTS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { Project } from "@/types/projects";
import { DEMO_PROJECT_NAME } from "@/constants/shared";

type UseDemoProjectParams = {
  workspaceName: string;
};

const getDemoProject = async ({ signal }: QueryFunctionContext) => {
  try {
    const { data } = await api.post<Project | null>(
      `${PROJECTS_REST_ENDPOINT}retrieve`,
      {
        name: DEMO_PROJECT_NAME,
      },
      {
        signal,
      },
    );

    return data;
  } catch (e) {
    return null;
  }
};

export default function useDemoProject(
  params: UseDemoProjectParams,
  options?: QueryConfig<Project | null>,
) {
  return useQuery({
    queryKey: ["project", params],
    queryFn: (context) => getDemoProject(context),
    ...options,
  });
}
