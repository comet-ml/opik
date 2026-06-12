import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { AI_SPEND_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { ModelTiers } from "./claudePricing";

export interface SpendSummaryResult {
  name: string;
  current: number | null;
  previous: number | null;
}

export interface SpendSummaryResponse {
  // Count metrics (total_messages, active_users, total_users).
  results: SpendSummaryResult[];
  // Per-model spend tiers for the current and previous windows; priced
  // FE-side so total spend and its trend reflect each model's rate.
  spend_current: ModelTiers[];
  spend_previous: ModelTiers[];
}

type UseAiSpendSummaryParams = {
  projectName: string;
  intervalStart: string;
  intervalEnd: string;
};

const getAiSpendSummary = async (
  { signal }: QueryFunctionContext,
  { projectName, intervalStart, intervalEnd }: UseAiSpendSummaryParams,
) => {
  const { data } = await api.post<SpendSummaryResponse>(
    `${AI_SPEND_REST_ENDPOINT}summary`,
    {
      project_name: projectName,
      interval_start: intervalStart,
      interval_end: intervalEnd,
    },
    { signal },
  );

  return data;
};

export default function useAiSpendSummary(
  params: UseAiSpendSummaryParams,
  options?: QueryConfig<SpendSummaryResponse>,
) {
  return useQuery({
    queryKey: ["ai-spend-summary", params],
    queryFn: (context) => getAiSpendSummary(context, params),
    ...options,
  });
}
