import { QueryConfig } from "@/api/api";
import { OptimizerHistoryResponse } from "@/types/agent-optimizer";
import { useQuery } from "@tanstack/react-query";

const BASE_AGENT_OPTIMIZER_URL = import.meta.env.VITE_BASE_AGENT_OPTIMIZER_URL || "http://localhost:8000";

export const AGENT_OPTIMIZER_HISTORY_KEY = "agent-optimizer-history";

type UseAgentOptimizerHistoryParams = {
  traceId: string;
};

const getAgentOptimizerHistory = async (
  traceId: string,
): Promise<OptimizerHistoryResponse> => {
  const response = await fetch(
    `${BASE_AGENT_OPTIMIZER_URL}/optimizer/session/${traceId}`,
    {
      credentials: "include",
    },
  );

  if (!response.ok) {
    if (response.status === 404) {
      // No session yet, return empty
      return {
        content: [],
        phase: "init",
        state: {
          phase: "init",
          traceId,
        },
      };
    }
    throw new Error("Failed to fetch optimizer history");
  }

  return response.json();
};

export default function useAgentOptimizerHistory(
  params: UseAgentOptimizerHistoryParams,
  options?: QueryConfig<OptimizerHistoryResponse>,
) {
  return useQuery({
    queryKey: [AGENT_OPTIMIZER_HISTORY_KEY, params],
    queryFn: () => getAgentOptimizerHistory(params.traceId),
    ...options,
  });
}
