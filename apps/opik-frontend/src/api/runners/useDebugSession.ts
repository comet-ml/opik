import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  DEBUG_SESSION_KEY,
  RUNNERS_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import { DebugSession } from "@/types/runners";

const getDebugSession = async (
  { signal }: QueryFunctionContext,
  sessionId: string,
) => {
  const { data } = await api.get<DebugSession>(
    `${RUNNERS_REST_ENDPOINT}debug/${sessionId}`,
    { signal },
  );
  return data;
};

export default function useDebugSession(
  sessionId: string | null,
  options?: QueryConfig<DebugSession | null>,
) {
  return useQuery({
    queryKey: [DEBUG_SESSION_KEY, sessionId],
    queryFn: async (context) => {
      if (!sessionId) return null;
      return getDebugSession(context, sessionId);
    },
    enabled: Boolean(sessionId),
    ...options,
  } as Parameters<typeof useQuery<DebugSession | null>>[0]);
}
