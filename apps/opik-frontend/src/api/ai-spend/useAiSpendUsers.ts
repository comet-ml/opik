import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { AI_SPEND_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { useAiSpend } from "@/contexts/AiSpendContext";

export interface SpendUserRow {
  user_uuid: string;
  user_email: string;
  user_display_name: string;
  model: string;
  total_estimated_cost: number | null;
  requests: number;
  skills: number;
  mcps: number;
  mcp_calls: number;
  repositories: string[];
  flags: string[];
}

export interface SpendUserPage {
  page: number;
  size: number;
  total: number;
  content: SpendUserRow[];
  sortable_by: string[];
}

type UseAiSpendUsersParams = {
  projectName: string;
  intervalStart: string;
  intervalEnd: string;
  page: number;
  size: number;
  workspaceName?: string;
};

const getAiSpendUsers = async (
  { signal }: QueryFunctionContext,
  {
    projectName,
    intervalStart,
    intervalEnd,
    page,
    size,
    workspaceName,
  }: UseAiSpendUsersParams,
) => {
  const { data } = await api.post<SpendUserPage>(
    `${AI_SPEND_REST_ENDPOINT}users`,
    {
      project_name: projectName,
      interval_start: intervalStart,
      interval_end: intervalEnd,
    },
    {
      signal,
      params: { page, size },
      ...(workspaceName && {
        headers: { "Comet-Workspace": workspaceName },
      }),
    },
  );

  return data;
};

export default function useAiSpendUsers(
  params: Omit<UseAiSpendUsersParams, "workspaceName">,
  options?: QueryConfig<SpendUserPage>,
) {
  const { spendWorkspaceName } = useAiSpend();
  const queryParams = { ...params, workspaceName: spendWorkspaceName };

  return useQuery({
    queryKey: ["ai-spend-users", queryParams],
    queryFn: (context) => getAiSpendUsers(context, queryParams),
    ...options,
  });
}
