import { QueryFunctionContext, useQueries } from "@tanstack/react-query";
import { TRACE_KEY } from "@/api/api";
import { getTraceById, UseTraceByIdParams } from "@/api/traces/useTraceById";

type UseTracesByIdsParams = {
  traceIds: string[];
  stripAttachments?: boolean;
};

// Fetches several traces by id in parallel, reusing useTraceById's fetcher and
// query-key shape so results share its cache (no double-fetching).
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
