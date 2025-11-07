import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig, SPANS_KEY, SPANS_REST_ENDPOINT } from "@/api/api";
import { Span, SPAN_TYPE } from "@/types/traces";
import { Filters } from "@/types/filters";
import { generateSearchByIDFilters, processFilters } from "@/lib/filters";
import { Sorting } from "@/types/sorting";
import { processSorting } from "@/lib/sorting";

export type UseSpansListParams = {
  projectId: string;
  traceId?: string;
  type?: SPAN_TYPE;
  filters?: Filters;
  sorting?: Sorting;
  search?: string;
  page: number;
  size: number;
  truncate?: boolean;
  stripAttachments?: boolean;
  exclude?: string[];
  fromTime?: string;
  toTime?: string;
};

export type UseSpansListResponse = {
  content: Span[];
  sortable_by: string[];
  total: number;
};

const getSpansList = async (
  { signal }: QueryFunctionContext,
  {
    projectId,
    traceId,
    type,
    filters,
    sorting,
    search,
    size,
    page,
    truncate,
    stripAttachments,
    exclude,
    fromTime,
    toTime,
  }: UseSpansListParams,
) => {
  const { data } = await api.get(SPANS_REST_ENDPOINT, {
    signal,
    params: {
      project_id: projectId,
      ...(traceId && { trace_id: traceId }),
      ...(type && { type }),
      ...processFilters(filters, generateSearchByIDFilters(search)),
      ...processSorting(sorting),
      ...(exclude && { exclude: JSON.stringify(exclude) }),
      ...(truncate !== undefined && { truncate }),
      ...(stripAttachments !== undefined && {
        strip_attachments: stripAttachments,
      }),
      size,
      page,
      ...(fromTime && { from_time: fromTime }),
      ...(toTime && { to_time: toTime }),
    },
  });

  return data;
};

export default function useSpansList(
  params: UseSpansListParams,
  options?: QueryConfig<UseSpansListResponse>,
) {
  return useQuery({
    queryKey: [SPANS_KEY, params],
    queryFn: (context) => getSpansList(context, params),
    ...options,
  });
}
