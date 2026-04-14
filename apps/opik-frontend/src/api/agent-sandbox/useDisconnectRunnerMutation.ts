import { useMutation, useQueryClient } from "@tanstack/react-query";

import api, { AGENT_SANDBOX_KEY, LOCAL_RUNNERS_REST_ENDPOINT } from "@/api/api";

const disconnectRunner = async (runnerId: string) => {
  await api.delete(`${LOCAL_RUNNERS_REST_ENDPOINT}${runnerId}`);
};

export default function useDisconnectRunnerMutation() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: disconnectRunner,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [AGENT_SANDBOX_KEY] });
    },
  });
}
