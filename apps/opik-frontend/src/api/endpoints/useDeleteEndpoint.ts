import { useMutation, useQueryClient } from "@tanstack/react-query";
import api, { ENDPOINTS_REST_ENDPOINT } from "@/api/api";

const deleteEndpoint = async (id: string) => {
  const { data } = await api.delete(`${ENDPOINTS_REST_ENDPOINT}${id}`);
  return data;
};

const useDeleteEndpoint = () => {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: deleteEndpoint,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["endpoints"] });
    },
  });
};

export default useDeleteEndpoint;
