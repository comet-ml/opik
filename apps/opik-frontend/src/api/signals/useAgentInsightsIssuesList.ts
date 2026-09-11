import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  AGENT_INSIGHTS_ISSUES_KEY,
  AGENT_INSIGHTS_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import {
  AGENT_INSIGHTS_ISSUE_STATUS,
  AgentInsightsIssuesPage,
} from "@/types/signals";
import { Sorting } from "@/types/sorting";
import { processSorting } from "@/lib/sorting";

type UseAgentInsightsIssuesListParams = {
  projectId: string;
  status?: AGENT_INSIGHTS_ISSUE_STATUS;
  fromDate?: string;
  toDate?: string;
  sorting?: Sorting;
  page: number;
  size: number;
};

const getIssuesList = async (
  { signal }: QueryFunctionContext,
  {
    projectId,
    status,
    fromDate,
    toDate,
    sorting,
    page,
    size,
  }: UseAgentInsightsIssuesListParams,
) => {
  const { data } = await api.get<AgentInsightsIssuesPage>(
    `${AGENT_INSIGHTS_REST_ENDPOINT}issues`,
    {
      signal,
      params: {
        project_id: projectId,
        ...(status && { status }),
        ...(fromDate && { from_date: fromDate }),
        ...(toDate && { to_date: toDate }),
        ...processSorting(sorting),
        page,
        size,
      },
    },
  );

  return data;
};

export default function useAgentInsightsIssuesList(
  params: UseAgentInsightsIssuesListParams,
  options?: QueryConfig<AgentInsightsIssuesPage>,
) {
  return useQuery({
    queryKey: [AGENT_INSIGHTS_ISSUES_KEY, params],
    queryFn: (context) => getIssuesList(context, params),
    ...options,
  });
}
