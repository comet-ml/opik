import useSpansList, { UseSpansListParams } from "@/api/traces/useSpansList";
import { Span } from "@/types/traces";
import { COLUMN_TYPE } from "@/types/shared";
import { Filter } from "@/types/filters";

export const COSTED_SPANS_LIMIT = 1000;
export const COST_SPANS_STALE_TIME = 60 * 1000;

const COSTED_FILTER: Filter = {
  id: "",
  field: "total_estimated_cost",
  type: COLUMN_TYPE.cost,
  operator: ">",
  value: 0,
};

const UNUSED_SPAN_FIELDS = [
  "input",
  "output",
  "feedback_scores",
  "comments",
  "tags",
  "error_info",
];

export const costedSpansParams = (
  projectId: string,
  traceId: string,
): UseSpansListParams => ({
  projectId,
  traceId,
  filters: [COSTED_FILTER],
  exclude: UNUSED_SPAN_FIELDS,
  truncate: true,
  stripAttachments: true,
  page: 1,
  size: COSTED_SPANS_LIMIT,
});

type UseTraceCostSpansParams = {
  projectId: string;
  traceId: string;
  spans?: Span[];
};

export default function useTraceCostSpans({
  projectId,
  traceId,
  spans,
}: UseTraceCostSpansParams) {
  const query = useSpansList(costedSpansParams(projectId, traceId), {
    enabled: !spans && Boolean(projectId) && Boolean(traceId),
    staleTime: COST_SPANS_STALE_TIME,
  });

  if (spans) {
    return { spans, isPending: false, isError: false, isTruncated: false };
  }

  const content = query.data?.content ?? [];
  return {
    spans: content,
    isPending: query.isPending,
    isError: query.isError,
    isTruncated: (query.data?.total ?? 0) > content.length,
  };
}
