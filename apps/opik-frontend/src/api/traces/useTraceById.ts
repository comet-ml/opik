import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig, TRACE_KEY, TRACES_REST_ENDPOINT } from "@/api/api";
import { Trace } from "@/types/traces";

type UseTraceByIdParams = {
  traceId: string;
};

// TODO add default value from cache
const getTraceById = async (
  { signal }: QueryFunctionContext,
  { traceId }: UseTraceByIdParams,
) => {
  const { data } = await api.get<Trace>(TRACES_REST_ENDPOINT + traceId, {
    signal,
  });

  return data;
};

export default function useTraceById(
  params: UseTraceByIdParams,
  options?: QueryConfig<Trace>,
) {
  return useQuery({
    queryKey: [TRACE_KEY, params],
    queryFn: (context) => getTraceById(context, params),
    ...options,
  });
}
