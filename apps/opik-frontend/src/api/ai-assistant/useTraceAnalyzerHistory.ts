import { QueryFunctionContext, useQuery } from "@tanstack/react-query";

import api, {
  BASE_OPIK_AI_URL,
  QueryConfig,
  TRACE_AI_ASSISTANT_KEY,
  TRACE_ANALYZER_REST_ENDPOINT,
} from "@/api/api";
import { TraceAnalyzerHistoryResponse } from "@/types/ai-assistant";

export type UseTraceAnalyzerHistoryParams = {
  traceId: string;
};

const getTraceAnalyzerHistory = async (
  { signal }: QueryFunctionContext,
  { traceId }: UseTraceAnalyzerHistoryParams,
) => {
  const { data } = await api.get<TraceAnalyzerHistoryResponse>(
    `${TRACE_ANALYZER_REST_ENDPOINT}${traceId}`,
    {
      baseURL: BASE_OPIK_AI_URL,
      signal,
    },
  );

  return data;
};

export default function useTraceAnalyzerHistory(
  params: UseTraceAnalyzerHistoryParams,
  options?: QueryConfig<TraceAnalyzerHistoryResponse>,
) {
  return useQuery({
    queryKey: [TRACE_AI_ASSISTANT_KEY, params],
    queryFn: (context) => getTraceAnalyzerHistory(context, params),
    ...options,
  });
}
