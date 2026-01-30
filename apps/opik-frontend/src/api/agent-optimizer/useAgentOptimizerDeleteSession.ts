import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AGENT_OPTIMIZER_HISTORY_KEY } from "./useAgentOptimizerHistory";

const BASE_AGENT_OPTIMIZER_URL =
  import.meta.env.VITE_BASE_AGENT_OPTIMIZER_URL || "http://localhost:5000";

type UseAgentOptimizerDeleteSessionParams = {
  traceId: string;
};

const deleteAgentOptimizerSession = async (traceId: string): Promise<void> => {
  const response = await fetch(
    `${BASE_AGENT_OPTIMIZER_URL}/optimizer/session/${traceId}`,
    {
      method: "DELETE",
      credentials: "include",
    },
  );

  if (!response.ok) {
    throw new Error("Failed to delete optimizer session");
  }
};

export default function useAgentOptimizerDeleteSession() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (params: UseAgentOptimizerDeleteSessionParams) =>
      deleteAgentOptimizerSession(params.traceId),
    onSuccess: (_, variables) => {
      queryClient.invalidateQueries({
        queryKey: [AGENT_OPTIMIZER_HISTORY_KEY, { traceId: variables.traceId }],
      });
    },
  });
}
