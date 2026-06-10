import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { AI_SPEND_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { useAiSpend } from "@/contexts/AiSpendContext";

export interface AiSpendBreakdownItemApi {
  label: string;
  total_tokens: number;
}

export interface AiSpendBreakdownResponse {
  lane_key: string;
  title: string;
  subtitle?: string;
  total_tokens: number;
  item_count: number;
  items: AiSpendBreakdownItemApi[];
}

type UseAiSpendLaneBreakdownParams = {
  laneKey: string;
  projectName: string;
  intervalStart: string;
  intervalEnd: string;
  userUuid?: string;
  workspaceName?: string;
};

const getAiSpendLaneBreakdown = async (
  { signal }: QueryFunctionContext,
  {
    laneKey,
    projectName,
    intervalStart,
    intervalEnd,
    userUuid,
    workspaceName,
  }: UseAiSpendLaneBreakdownParams,
) => {
  const { data } = await api.post<AiSpendBreakdownResponse>(
    `${AI_SPEND_REST_ENDPOINT}composition/${laneKey}/breakdown`,
    {
      project_name: projectName,
      interval_start: intervalStart,
      interval_end: intervalEnd,
      ...(userUuid && { user_id: userUuid }),
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

export default function useAiSpendLaneBreakdown(
  params: Omit<UseAiSpendLaneBreakdownParams, "workspaceName">,
  options?: QueryConfig<AiSpendBreakdownResponse>,
) {
  const { spendWorkspaceName } = useAiSpend();
  const queryParams = { ...params, workspaceName: spendWorkspaceName };

  return useQuery({
    queryKey: ["ai-spend-lane-breakdown", queryParams],
    queryFn: (context) => getAiSpendLaneBreakdown(context, queryParams),
    enabled: Boolean(params.laneKey),
    ...options,
  });
}
