import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  BASE_OPIK_AI_URL,
  QueryConfig,
  TRACE_AI_ASSISTANT_KEY,
} from "@/api/api";

export type TraceAnalyzerLLMMessage = {
  id: string;
  role: "user" | "assistant";
  content: string;
};

export type TraceAnalyzerHistoryResponse = {
  content: TraceAnalyzerLLMMessage[];
};

export type UseTraceAnalyzerHistoryParams = {
  traceId: string;
};

const getTraceAnalyzerHistory = async (
  { signal }: QueryFunctionContext,
  { traceId }: UseTraceAnalyzerHistoryParams,
) => {
  const { data } = await api.get<TraceAnalyzerHistoryResponse>(
    `/trace-analyzer/session/${traceId}`,
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
