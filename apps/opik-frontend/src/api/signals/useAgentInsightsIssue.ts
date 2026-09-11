import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  AGENT_INSIGHTS_ISSUE_KEY,
  AGENT_INSIGHTS_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import { AgentInsightsIssueWithDetails } from "@/types/signals";

type UseAgentInsightsIssueParams = {
  issueId: string;
  projectId: string;
  fromDate?: string;
  toDate?: string;
};

const getIssue = async (
  { signal }: QueryFunctionContext,
  { issueId, projectId, fromDate, toDate }: UseAgentInsightsIssueParams,
) => {
  const { data } = await api.get<AgentInsightsIssueWithDetails>(
    `${AGENT_INSIGHTS_REST_ENDPOINT}issues/${issueId}`,
    {
      signal,
      params: {
        project_id: projectId,
        ...(fromDate && { from_date: fromDate }),
        ...(toDate && { to_date: toDate }),
      },
    },
  );

  return data;
};

export default function useAgentInsightsIssue(
  params: UseAgentInsightsIssueParams,
  options?: QueryConfig<AgentInsightsIssueWithDetails>,
) {
  return useQuery({
    queryKey: [AGENT_INSIGHTS_ISSUE_KEY, params],
    queryFn: (context) => getIssue(context, params),
    ...options,
  });
}
