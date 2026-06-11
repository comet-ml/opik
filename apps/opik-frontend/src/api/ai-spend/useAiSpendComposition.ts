import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { AI_SPEND_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { useAiSpend } from "@/contexts/AiSpendContext";

export interface AiSpendLaneApi {
  key: string;
  label: string;
  total_tokens: number;
  has_breakdown: boolean;
}

export interface AiSpendSideApi {
  total_tokens: number;
  lanes: AiSpendLaneApi[];
}

export interface AiSpendHarnessApi {
  key: string;
  label: string;
  total_estimated_cost: number | null;
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
  workspaceName?: string;
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
    {
      signal,
      ...(params.workspaceName && {
        headers: { "Comet-Workspace": params.workspaceName },
      }),
    },
  );

  return data;
};

export default function useAiSpendComposition(
  params: Omit<UseAiSpendCompositionParams, "workspaceName">,
  options?: QueryConfig<AiSpendCompositionResponse>,
) {
  const { spendWorkspaceName } = useAiSpend();
  const queryParams = { ...params, workspaceName: spendWorkspaceName };

  return useQuery({
    queryKey: ["ai-spend-composition", queryParams],
    queryFn: (context) => getAiSpendComposition(context, queryParams),
    ...options,
  });
}
