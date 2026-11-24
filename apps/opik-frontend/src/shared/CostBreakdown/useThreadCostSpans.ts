import { useMemo } from "react";
import {
  QueryFunctionContext,
  UseQueryResult,
  useQueries,
} from "@tanstack/react-query";
import { SPANS_KEY } from "@/api/api";
import { UseSpansListResponse, getSpansList } from "@/api/traces/useSpansList";
import useTracesList from "@/api/traces/useTracesList";
import { Span, TRACE_VISIBILITY_MODE } from "@/types/traces";
import { COLUMN_TYPE } from "@/types/shared";
import {
  COST_SPANS_STALE_TIME,
  COSTED_FILTER,
  costedSpansParams,
} from "@/shared/CostBreakdown/useTraceCostSpans";

// No endpoint filters spans by thread, so each trace's spans are a request of their own; the cap bounds how many
// requests one hover can fire.
export const THREAD_TRACES_LIMIT = 20;

const TRACE_FIELDS_NOT_NEEDED = [
  "input",
  "output",
  "metadata",
  "feedback_scores",
  "span_feedback_scores",
  "comments",
  "tags",
  "guardrails_validations",
  "experiment",
  "annotation_queues",
];

export const combineSpanQueries = (
  queries: UseQueryResult<UseSpansListResponse>[],
) => ({
  spans: queries.flatMap((query): Span[] => query.data?.content ?? []),
  isPending: queries.some((query) => query.isPending),
  isError: queries.some((query) => query.isError),
  isTruncated: queries.some(
    (query) => (query.data?.total ?? 0) > (query.data?.content.length ?? 0),
  ),
});

type UseThreadCostSpansParams = {
  projectId: string;
  threadId: string;
};

export default function useThreadCostSpans({
  projectId,
  threadId,
}: UseThreadCostSpansParams) {
  const tracesQuery = useTracesList(
    {
      projectId,
      filters: [
        {
          id: "",
          field: "thread_id",
          type: COLUMN_TYPE.string,
          operator: "=",
          value: threadId,
        },
        COSTED_FILTER,
      ],
      sorting: [{ id: "start_time", desc: false }],
      exclude: TRACE_FIELDS_NOT_NEEDED,
      truncate: true,
      stripAttachments: true,
      visibilityMode: TRACE_VISIBILITY_MODE.all,
      page: 1,
      size: THREAD_TRACES_LIMIT,
    },
    {
      enabled: Boolean(projectId) && Boolean(threadId),
      staleTime: COST_SPANS_STALE_TIME,
    },
  );

  const traceIds = useMemo(
    () => (tracesQuery.data?.content ?? []).map((trace) => trace.id),
    [tracesQuery.data],
  );

  const spanQueries = useQueries({
    queries: traceIds.map((traceId) => {
      const params = costedSpansParams(projectId, traceId);
      return {
        queryKey: [SPANS_KEY, params],
        queryFn: (context: QueryFunctionContext) =>
          getSpansList(context, params) as Promise<UseSpansListResponse>,
        staleTime: COST_SPANS_STALE_TIME,
      };
    }),
    combine: combineSpanQueries,
  });

  return {
    spans: spanQueries.spans,
    isPending: tracesQuery.isPending || spanQueries.isPending,
    isError: tracesQuery.isError || spanQueries.isError,
    isTruncated: spanQueries.isTruncated,
    tracesShown: traceIds.length,
    tracesTotal: tracesQuery.data?.total ?? 0,
  };
}
