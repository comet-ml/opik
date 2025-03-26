import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  AUTOMATIONS_KEY,
  AUTOMATIONS_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import { EvaluatorRuleLogItem } from "@/types/automations";

type UseRulesLogsListParams = {
  ruleId: string;
};

export type UseRulesLogsListResponse = {
  content: EvaluatorRuleLogItem[];
};

const getRulesLogsList = async (
  { signal }: QueryFunctionContext,
  { ruleId }: UseRulesLogsListParams,
) => {
  const { data } = await api.get<UseRulesLogsListResponse>(
    `${AUTOMATIONS_REST_ENDPOINT}evaluators/${ruleId}/logs`,
    {
      signal,
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
