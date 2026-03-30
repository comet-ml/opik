import { useQuery } from "@tanstack/react-query";

import api, { AGENT_SANDBOX_KEY, LOCAL_RUNNERS_REST_ENDPOINT } from "@/api/api";
import { SandboxJob, SandboxJobStatus } from "@/types/agent-sandbox";

const JOB_POLL_INTERVAL = 2000;

type UseSandboxJobStatusParams = {
  jobId: string;
};

const isTerminal = (status?: SandboxJobStatus) =>
  status === SandboxJobStatus.COMPLETED ||
  status === SandboxJobStatus.FAILED ||
  status === SandboxJobStatus.CANCELLED;

const getJobStatus = async (
  jobId: string,
  signal: AbortSignal,
): Promise<SandboxJob> => {
  const { data } = await api.get(
    `${LOCAL_RUNNERS_REST_ENDPOINT}jobs/${jobId}`,
    { signal },
  );
  return data;
};

export default function useSandboxJobStatus({
  jobId,
}: UseSandboxJobStatusParams) {
  return useQuery({
    queryKey: [AGENT_SANDBOX_KEY, "jobs", jobId],
    queryFn: ({ signal }) => getJobStatus(jobId, signal),
    enabled: !!jobId,
    refetchInterval: (query) =>
      isTerminal(query.state.data?.status) ? false : JOB_POLL_INTERVAL,
  });
}
