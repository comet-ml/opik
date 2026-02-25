import { useMutation, useQueryClient } from "@tanstack/react-query";
import api, {
  DEBUG_SESSION_KEY,
  RUNNERS_REST_ENDPOINT,
} from "@/api/api";

const useDebugStep = () => {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({
      sessionId,
      command,
    }: {
      sessionId: string;
      command: string;
    }) => {
      await api.post(`${RUNNERS_REST_ENDPOINT}debug/${sessionId}/step`, {
        command,
      });
    },
    onSettled: (_data, _error, variables) => {
      queryClient.invalidateQueries({
        queryKey: [DEBUG_SESSION_KEY, variables.sessionId],
      });
    },
  });
};

export default useDebugStep;
