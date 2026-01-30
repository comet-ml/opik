import { useQuery } from "@tanstack/react-query";
import api, { ENDPOINTS_REST_ENDPOINT } from "@/api/api";

export type Endpoint = {
  id: string;
  project_id: string;
  name: string;
  url: string;
  secret?: string;
  schema_json: string | null;
  created_at: string;
  created_by: string;
  last_updated_at: string;
  last_updated_by: string;
};

export type EndpointPage = {
  page: number;
  size: number;
  total: number;
  content: Endpoint[];
  sortable_by: string[];
};

type UseEndpointsParams = {
  projectId: string;
  page?: number;
  size?: number;
  name?: string;
};

const getEndpoints = async ({
  projectId,
  page = 1,
  size = 100,
  name,
}: UseEndpointsParams) => {
  const params = new URLSearchParams({
    project_id: projectId,
    page: String(page),
    size: String(size),
  });

  if (name) {
    params.append("name", name);
  }

  const { data } = await api.get<EndpointPage>(
    `${ENDPOINTS_REST_ENDPOINT}?${params.toString()}`,
  );
  return data;
};

const useEndpoints = (params: UseEndpointsParams) => {
  return useQuery({
    queryKey: ["endpoints", params],
    queryFn: () => getEndpoints(params),
  });
};

export default useEndpoints;
