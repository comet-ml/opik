import { useQuery, UseQueryOptions } from "@tanstack/react-query";

import api, { AGENT_SANDBOX_KEY, LOCAL_RUNNERS_REST_ENDPOINT } from "@/api/api";
import { LocalRunner, RunnerConnectionStatus } from "@/types/agent-sandbox";

const POLL_INTERVAL_WHILE_DISCONNECTED = 3000;
const POLL_INTERVAL_WHILE_CONNECTED = 10000;

type UseRunnerConnectionStatusParams = {
  projectId: string;
  runnerType?: "connect" | "endpoint";
};

interface RunnersListResponse {
  content: LocalRunner[];
  total: number;
}

const getConnectedRunner = async (
  projectId: string,
  signal: AbortSignal,
  runnerType?: "connect" | "endpoint",
): Promise<LocalRunner | null> => {
  const { data } = await api.get<RunnersListResponse>(
    LOCAL_RUNNERS_REST_ENDPOINT,
    {
      signal,
      params: { project_id: projectId, size: 50 },
    },
  );
  const runners = runnerType
    ? data.content.filter((r) => r.type === runnerType)
    : data.content;
  return (
    runners.find((r) => r.status === RunnerConnectionStatus.CONNECTED) ??
    runners[0] ??
    null
  );
};

export default function useSandboxConnectionStatus(
  { projectId, runnerType }: UseRunnerConnectionStatusParams,
  options?: Partial<UseQueryOptions<LocalRunner | null>>,
) {
  return useQuery({
    queryKey: [
      AGENT_SANDBOX_KEY,
      { projectId, runnerType },
      "connection-status",
    ],
    queryFn: ({ signal }) => getConnectedRunner(projectId, signal, runnerType),
    enabled: !!projectId,
    refetchInterval: (query) => {
      const runner = query.state.data;
      const isFullyReady =
        runner?.status === RunnerConnectionStatus.CONNECTED &&
        (runner.agents?.length ?? 0) > 0;
      return isFullyReady
        ? POLL_INTERVAL_WHILE_CONNECTED
        : POLL_INTERVAL_WHILE_DISCONNECTED;
    },
    ...options,
  });
}
