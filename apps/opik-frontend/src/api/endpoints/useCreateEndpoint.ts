import { useMutation, useQueryClient } from "@tanstack/react-query";
import api, { ENDPOINTS_REST_ENDPOINT } from "@/api/api";

type CreateEndpointParams = {
  project_id: string;
  name: string;
  url: string;
  secret: string;
  schema_json?: string | null;
};

const createEndpoint = async (params: CreateEndpointParams) => {
  const { data } = await api.post(ENDPOINTS_REST_ENDPOINT, params);
  return data;
};

const useCreateEndpoint = () => {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: createEndpoint,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["endpoints"] });
    },
  });
};

export default useCreateEndpoint;
