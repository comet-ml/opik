import { QueryFunctionContext, useQueries } from "@tanstack/react-query";
import { TRACE_KEY } from "@/api/api";
import { getTraceById, UseTraceByIdParams } from "@/api/traces/useTraceById";

type UseTracesByIdsParams = {
  traceIds: string[];
  stripAttachments?: boolean;
};

// Parallel trace fetches reusing useTraceById's fetcher + query key (shared cache).
export default function useTracesByIds({
  traceIds,
  stripAttachments,
}: UseTracesByIdsParams) {
  return useQueries({
    queries: traceIds.map((traceId) => {
      const p: UseTraceByIdParams = { traceId, stripAttachments };

      return {
        queryKey: [TRACE_KEY, p],
        queryFn: (context: QueryFunctionContext) => getTraceById(context, p),
      };
    }),
  });
}
