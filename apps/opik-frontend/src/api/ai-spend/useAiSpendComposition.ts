import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { AI_SPEND_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { ModelTiers } from "./claudePricing";

export interface AiSpendLaneApi {
  key: string;
  label: string;
  total_tokens: number;
  // Per-model cache-tier sums from cc.billing; priced FE-side (claudePricing).
  by_model: ModelTiers[];
  has_breakdown: boolean;
}

export interface AiSpendSideApi {
  total_tokens: number;
  lanes: AiSpendLaneApi[];
}

export interface AiSpendHarnessApi {
  key: string;
  label: string;
}

export interface AiSpendCompositionResponse {
  input: AiSpendSideApi;
  harness: AiSpendHarnessApi[];
  output: AiSpendSideApi;
}

type UseAiSpendCompositionParams = {
  projectName: string;
  intervalStart: string;
  intervalEnd: string;
  userUuid?: string;
};

const buildBody = ({
  projectName,
  intervalStart,
  intervalEnd,
  userUuid,
}: UseAiSpendCompositionParams) => ({
  project_name: projectName,
  interval_start: intervalStart,
  interval_end: intervalEnd,
  ...(userUuid && { user_id: userUuid }),
});

const getAiSpendComposition = async (
  { signal }: QueryFunctionContext,
  params: UseAiSpendCompositionParams,
) => {
  const { data } = await api.post<AiSpendCompositionResponse>(
    `${AI_SPEND_REST_ENDPOINT}composition`,
    buildBody(params),
    { signal },
  );

  return data;
};

export default function useAiSpendComposition(
  params: UseAiSpendCompositionParams,
  options?: QueryConfig<AiSpendCompositionResponse>,
) {
  return useQuery({
    queryKey: ["ai-spend-composition", params],
    queryFn: (context) => getAiSpendComposition(context, params),
    ...options,
  });
}
