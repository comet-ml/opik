import { QueryFunctionContext, useQueries } from "@tanstack/react-query";
import { SPAN_KEY } from "@/api/api";
import { getSpanById, UseSpanByIdParams } from "@/api/traces/useSpanById";

type UseSpansByIdsParams = {
  spanIds: string[];
  stripAttachments?: boolean;
};

export default function useSpansByIds({
  spanIds,
  stripAttachments,
}: UseSpansByIdsParams) {
  return useQueries({
    queries: spanIds.map((spanId) => {
      const p: UseSpanByIdParams = { spanId, stripAttachments };

      return {
        queryKey: [SPAN_KEY, p],
        queryFn: (context: QueryFunctionContext) => getSpanById(context, p),
      };
    }),
  });
}
