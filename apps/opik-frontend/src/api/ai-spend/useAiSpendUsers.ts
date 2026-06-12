import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import { ColumnSort } from "@tanstack/react-table";
import api, { AI_SPEND_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { processSorting } from "@/lib/sorting";

export interface SpendUserRow {
  user_uuid: string;
  user_email: string;
  user_display_name: string;
  model: string;
  input_tokens?: number | null;
  cache_read_tokens?: number | null;
  cache_creation_tokens?: number | null;
  output_tokens?: number | null;
  total_tokens?: number | null;
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
  name?: string;
  sorting?: ColumnSort[];
};

const getAiSpendUsers = async (
  { signal }: QueryFunctionContext,
  {
    projectName,
    intervalStart,
    intervalEnd,
    page,
    size,
    name,
    sorting,
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
      params: { page, size, ...(name && { name }), ...processSorting(sorting) },
    },
  );

  return data;
};

export default function useAiSpendUsers(
  params: UseAiSpendUsersParams,
  options?: QueryConfig<SpendUserPage>,
) {
  return useQuery({
    queryKey: ["ai-spend-users", params],
    queryFn: (context) => getAiSpendUsers(context, params),
    ...options,
  });
}
