import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import { AxiosError } from "axios";
import api, {
  AGENT_INSIGHTS_JOB_KEY,
  AGENT_INSIGHTS_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import { AgentInsightsJob } from "@/types/signals";

type UseAgentInsightsJobParams = {
  projectId: string;
};

// Returns the project's Agent Insights job, or `null` when none exists yet
// (the backend 404s) — callers use that to show the onboarding/empty state.
const getJob = async (
  { signal }: QueryFunctionContext,
  { projectId }: UseAgentInsightsJobParams,
) => {
  try {
    const { data } = await api.get<AgentInsightsJob>(
      `${AGENT_INSIGHTS_REST_ENDPOINT}jobs/${projectId}`,
      { signal },
    );
    return data;
  } catch (error) {
    if ((error as AxiosError)?.response?.status === 404) {
      return null;
    }
    throw error;
  }
};

export default function useAgentInsightsJob(
  params: UseAgentInsightsJobParams,
  options?: QueryConfig<AgentInsightsJob | null>,
) {
  return useQuery({
    queryKey: [AGENT_INSIGHTS_JOB_KEY, params],
    queryFn: (context) => getJob(context, params),
    ...options,
    enabled: (options?.enabled ?? true) && Boolean(params.projectId),
  });
}
