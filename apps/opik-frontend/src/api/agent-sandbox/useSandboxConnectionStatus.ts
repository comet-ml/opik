import { useQuery, UseQueryOptions } from "@tanstack/react-query";

import api, { AGENT_SANDBOX_KEY, LOCAL_RUNNERS_REST_ENDPOINT } from "@/api/api";
import { LocalRunner, RunnerConnectionStatus } from "@/types/agent-sandbox";

const POLL_INTERVAL_WHILE_DISCONNECTED = 3000;
const POLL_INTERVAL_WHILE_CONNECTED = 10000;

type UseRunnerConnectionStatusParams = {
  projectId: string;
};

interface RunnersListResponse {
  content: LocalRunner[];
  total: number;
}

const getConnectedRunner = async (
  projectId: string,
  signal: AbortSignal,
): Promise<LocalRunner | null> => {
  const { data } = await api.get<RunnersListResponse>(
    LOCAL_RUNNERS_REST_ENDPOINT,
    {
      signal,
      params: { project_id: projectId, size: 50 },
    },
  );
  // Prefer connected runner; fall back to most recent (e.g. DISCONNECTED after
  // a connect+disconnect cycle — backend evicts old runners on new connections).
  return (
    data.content.find((r) => r.status === RunnerConnectionStatus.CONNECTED) ??
    data.content[0] ??
    null
  );
};

export default function useSandboxConnectionStatus(
  { projectId }: UseRunnerConnectionStatusParams,
  options?: Partial<UseQueryOptions<LocalRunner | null>>,
) {
  return useQuery({
    queryKey: [AGENT_SANDBOX_KEY, { projectId }, "connection-status"],
    queryFn: ({ signal }) => getConnectedRunner(projectId, signal),
    enabled: !!projectId,
    refetchInterval: (query) =>
      query.state.data?.status === RunnerConnectionStatus.CONNECTED
        ? POLL_INTERVAL_WHILE_CONNECTED
        : POLL_INTERVAL_WHILE_DISCONNECTED,
    ...options,
  });
}
