import { useMutation, useQueryClient } from "@tanstack/react-query";
import api, { ENDPOINTS_REST_ENDPOINT } from "@/api/api";

type UpdateEndpointParams = {
  id: string;
  name?: string;
  url?: string;
  secret?: string;
  schema_json?: string | null;
};

const updateEndpoint = async ({ id, ...params }: UpdateEndpointParams) => {
  const { data } = await api.patch(`${ENDPOINTS_REST_ENDPOINT}${id}`, params);
  return data;
};

const useUpdateEndpoint = () => {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: updateEndpoint,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["endpoints"] });
    },
  });
};

export default useUpdateEndpoint;
