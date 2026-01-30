import { useQuery } from "@tanstack/react-query";
import api, { ENDPOINTS_REST_ENDPOINT, PROJECTS_REST_ENDPOINT } from "@/api/api";
import { Endpoint, EndpointPage } from "./useEndpoints";

type UseAllEndpointsParams = {
  workspaceName: string;
  enabled?: boolean;
};

type ProjectsResponse = {
  content: Array<{ id: string; name: string }>;
  total: number;
};

const getAllEndpoints = async (workspaceName: string): Promise<Endpoint[]> => {
  // First fetch all projects
  const { data: projectsData } = await api.get<ProjectsResponse>(
    PROJECTS_REST_ENDPOINT,
    {
      params: {
        workspace_name: workspaceName,
        page: 1,
        size: 100,
      },
    }
  );

  const projects = projectsData.content || [];

  // Then fetch endpoints for each project
  const endpointPromises = projects.map(async (project) => {
    try {
      const { data } = await api.get<EndpointPage>(
        `${ENDPOINTS_REST_ENDPOINT}?project_id=${project.id}&page=1&size=100`
      );
      return data.content || [];
    } catch {
      return [];
    }
  });

  const endpointArrays = await Promise.all(endpointPromises);
  return endpointArrays.flat();
};

const useAllEndpoints = ({ workspaceName, enabled = true }: UseAllEndpointsParams) => {
  return useQuery({
    queryKey: ["endpoints", "all", workspaceName],
    queryFn: () => getAllEndpoints(workspaceName),
    enabled,
  });
};

export default useAllEndpoints;
