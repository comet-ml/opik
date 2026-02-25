import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  DEBUG_GRAPH_KEY,
  RUNNERS_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import { DebugGraph } from "@/types/runners";

const getDebugGraph = async (
  { signal }: QueryFunctionContext,
  sessionId: string,
) => {
  const { data } = await api.get<DebugGraph>(
    `${RUNNERS_REST_ENDPOINT}debug/${sessionId}/graph`,
    { signal },
  );
  return data;
};

export default function useDebugGraph(
  sessionId: string | null,
  options?: QueryConfig<DebugGraph | null>,
) {
  return useQuery({
    queryKey: [DEBUG_GRAPH_KEY, sessionId],
    queryFn: async (context) => {
      if (!sessionId) return null;
      return getDebugGraph(context, sessionId);
    },
    enabled: Boolean(sessionId),
    ...options,
  } as Parameters<typeof useQuery<DebugGraph | null>>[0]);
}
