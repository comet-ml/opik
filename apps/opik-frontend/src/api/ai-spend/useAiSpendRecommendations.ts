import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { AI_SPEND_REST_ENDPOINT, QueryConfig } from "@/api/api";

export interface SpendRecommendation {
  id: string;
  title: string;
  body: string;
  impact: string;
  est_saving: number | null;
  docs_url?: string;
  related_lane_key?: string;
}

export interface SpendRecommendationsResponse {
  total_savings: number | null;
  items: SpendRecommendation[];
}

type UseAiSpendRecommendationsParams = {
  projectName: string;
  intervalStart: string;
  intervalEnd: string;
  userUuid?: string;
};

const getAiSpendRecommendations = async (
  { signal }: QueryFunctionContext,
  {
    projectName,
    intervalStart,
    intervalEnd,
    userUuid,
  }: UseAiSpendRecommendationsParams,
) => {
  const { data } = await api.post<SpendRecommendationsResponse>(
    `${AI_SPEND_REST_ENDPOINT}recommendations`,
    {
      project_name: projectName,
      interval_start: intervalStart,
      interval_end: intervalEnd,
      ...(userUuid && { user_id: userUuid }),
    },
    { signal },
  );

  return data;
};

export default function useAiSpendRecommendations(
  params: UseAiSpendRecommendationsParams,
  options?: QueryConfig<SpendRecommendationsResponse>,
) {
  return useQuery({
    queryKey: ["ai-spend-recommendations", params],
    queryFn: (context) => getAiSpendRecommendations(context, params),
    ...options,
  });
}
