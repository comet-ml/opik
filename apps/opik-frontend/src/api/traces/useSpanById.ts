import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig, SPAN_KEY, SPANS_REST_ENDPOINT } from "@/api/api";
import { Span } from "@/types/traces";

export type UseSpanByIdParams = {
  spanId: string;
  stripAttachments?: boolean;
};

export const getSpanById = async (
  { signal }: QueryFunctionContext,
  { spanId, stripAttachments }: UseSpanByIdParams,
) => {
  const { data } = await api.get<Span>(SPANS_REST_ENDPOINT + spanId, {
    signal,
    params: {
      ...(stripAttachments !== undefined && {
        strip_attachments: stripAttachments,
      }),
    },
  });

  return data;
};

export default function useSpanById(
  params: UseSpanByIdParams,
  options?: QueryConfig<Span>,
) {
  return useQuery({
    queryKey: [SPAN_KEY, params],
    queryFn: (context) => getSpanById(context, params),
    ...options,
  });
}
