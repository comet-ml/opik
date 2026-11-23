import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  AUTOMATIONS_KEY,
  AUTOMATIONS_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import { EvaluatorRuleLogItem } from "@/types/automations";

type UseRulesLogsListParams = {
  ruleId: string;
  traceId?: string;
};

export type UseRulesLogsListResponse = {
  content: EvaluatorRuleLogItem[];
};

const getRulesLogsList = async (
  { signal }: QueryFunctionContext,
  { ruleId, traceId }: UseRulesLogsListParams,
) => {
  const { data } = await api.get<UseRulesLogsListResponse>(
    `${AUTOMATIONS_REST_ENDPOINT}evaluators/${ruleId}/logs`,
    {
      signal,
      params: { trace_id: traceId },
    },
  );

  return data;
};

export default function useRulesLogsList(
  params: UseRulesLogsListParams,
  options?: QueryConfig<UseRulesLogsListResponse>,
) {
  return useQuery({
    queryKey: [AUTOMATIONS_KEY, params],
    queryFn: (context) => getRulesLogsList(context, params),
    ...options,
  });
}
