import { useQuery } from "@tanstack/react-query";

import api, { AGENT_SANDBOX_KEY, LOCAL_RUNNERS_REST_ENDPOINT } from "@/api/api";
import { SandboxPairCode } from "@/types/agent-sandbox";

type UseSandboxPairCodeParams = {
  projectId: string;
};

const getPairCode = async (
  projectId: string,
  signal: AbortSignal,
): Promise<SandboxPairCode> => {
  const { data } = await api.post(
    `${LOCAL_RUNNERS_REST_ENDPOINT}pairs`,
    { project_id: projectId },
    { signal },
  );
  return {
    pair_code: data.pairing_code,
    runner_id: data.runner_id,
    expires_in_seconds: data.expires_in_seconds,
    created_at: Date.now(),
  };
};

export default function useSandboxPairCode({
  projectId,
}: UseSandboxPairCodeParams) {
  return useQuery({
    queryKey: [AGENT_SANDBOX_KEY, "pair-code", { projectId }],
    queryFn: ({ signal }) => getPairCode(projectId, signal),
    enabled: !!projectId,
    retry: false,
    refetchOnWindowFocus: false,
    refetchOnMount: false,
    staleTime: (query) => {
      const data = query.state.data;
      if (!data) return 0;
      return data.expires_in_seconds * 1000;
    },
  });
}
