import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { AI_SPEND_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { useAiSpend } from "@/contexts/AiSpendContext";

export interface SpendSummaryResult {
  name: string;
  current: number | null;
  previous: number | null;
}

export interface SpendSummaryResponse {
  results: SpendSummaryResult[];
}

type UseAiSpendSummaryParams = {
  projectName: string;
  intervalStart: string;
  intervalEnd: string;
  workspaceName?: string;
};

const getAiSpendSummary = async (
  { signal }: QueryFunctionContext,
  {
    projectName,
    intervalStart,
    intervalEnd,
    workspaceName,
  }: UseAiSpendSummaryParams,
) => {
  const { data } = await api.post<SpendSummaryResponse>(
    `${AI_SPEND_REST_ENDPOINT}summary`,
    {
      project_name: projectName,
      interval_start: intervalStart,
      interval_end: intervalEnd,
    },
    {
      signal,
      ...(workspaceName && {
        headers: { "Comet-Workspace": workspaceName },
      }),
    },
  );

  return data;
};

export default function useAiSpendSummary(
  params: Omit<UseAiSpendSummaryParams, "workspaceName">,
  options?: QueryConfig<SpendSummaryResponse>,
) {
  const { spendWorkspaceName } = useAiSpend();
  const queryParams = { ...params, workspaceName: spendWorkspaceName };

  return useQuery({
    queryKey: ["ai-spend-summary", queryParams],
    queryFn: (context) => getAiSpendSummary(context, queryParams),
    ...options,
  });
}
