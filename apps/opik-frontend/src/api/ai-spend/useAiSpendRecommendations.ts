import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { AI_SPEND_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { useAiSpend } from "@/contexts/AiSpendContext";

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
  workspaceName?: string;
};

const getAiSpendRecommendations = async (
  { signal }: QueryFunctionContext,
  {
    projectName,
    intervalStart,
    intervalEnd,
    userUuid,
    workspaceName,
  }: UseAiSpendRecommendationsParams,
) => {
  const { data } = await api.post<SpendRecommendationsResponse>(
    `${AI_SPEND_REST_ENDPOINT}recommendations`,
    {
      project_name: projectName,
      interval_start: intervalStart,
      interval_end: intervalEnd,
      ...(userUuid && {
        filters: [
          {
            field: "metadata",
            operator: "=",
            key: "cc.identity.user_uuid",
            value: userUuid,
          },
        ],
      }),
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

export default function useAiSpendRecommendations(
  params: Omit<UseAiSpendRecommendationsParams, "workspaceName">,
  options?: QueryConfig<SpendRecommendationsResponse>,
) {
  const { spendWorkspaceName } = useAiSpend();
  const queryParams = { ...params, workspaceName: spendWorkspaceName };

  return useQuery({
    queryKey: ["ai-spend-recommendations", queryParams],
    queryFn: (context) => getAiSpendRecommendations(context, queryParams),
    ...options,
  });
}
