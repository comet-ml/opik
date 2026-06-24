import { useMemo } from "react";
import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import find from "lodash/find";

import api, { QueryConfig, SPANS_KEY, SPANS_REST_ENDPOINT } from "@/api/api";
import { Span, Trace } from "@/types/traces";

export type SpanWithOptionalPayload = Omit<Span, "input" | "output"> & {
  input?: Span["input"] | null;
  output?: Span["output"] | null;
};

type UseSelectedSpanDataParams = {
  projectId: string;
  spanId: string;
  traceId: string;
  spans?: SpanWithOptionalPayload[];
  trace?: Trace;
  stripAttachments?: boolean;
};

const hasFullSpanData = (span?: SpanWithOptionalPayload): span is Span =>
  Boolean(
    span &&
      span.input !== null &&
      span.input !== undefined &&
      span.output !== null &&
      span.output !== undefined,
  );

const toSpanPlaceholder = (span: SpanWithOptionalPayload): Span => ({
  ...span,
  input: span.input ?? {},
  output: span.output ?? {},
});

type UseSpanByIdParams = {
  spanId: string;
  stripAttachments?: boolean;
};

const getSpanById = async (
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

export default function useSelectedSpanData(
  {
    projectId,
    spanId,
    traceId,
    spans,
    trace,
    stripAttachments = true,
  }: UseSelectedSpanDataParams,
  options?: QueryConfig<Span>,
) {
  const selectedSpanFromList = useMemo(
    () =>
      find(
        spans || [],
        (span: SpanWithOptionalPayload) =>
          span.id === spanId && span.trace_id === traceId,
      ),
    [spanId, spans, traceId],
  );
  const shouldFetchSelectedSpan =
    Boolean(projectId) &&
    Boolean(spanId) &&
    !hasFullSpanData(selectedSpanFromList);

  const {
    data: selectedSpanData,
    isError: isSelectedSpanError,
    isFetching: isSelectedSpanFetching,
    isPlaceholderData: isSelectedSpanPlaceholderData,
  } = useQuery({
    queryKey: [SPANS_KEY, { projectId, spanId, stripAttachments }],
    queryFn: (context) => getSpanById(context, { spanId, stripAttachments }),
    ...options,
    placeholderData: selectedSpanFromList as Span | undefined,
    enabled: shouldFetchSelectedSpan && (options?.enabled ?? true),
  });

  const validSelectedSpanData =
    !isSelectedSpanPlaceholderData && selectedSpanData?.trace_id === traceId
      ? selectedSpanData
      : undefined;
  const selectedSpanFromListHasFullData = hasFullSpanData(selectedSpanFromList);
  const selectedSpanDataToView: Span | undefined =
    selectedSpanFromListHasFullData
      ? selectedSpanFromList
      : validSelectedSpanData ??
        (selectedSpanFromList
          ? toSpanPlaceholder(selectedSpanFromList)
          : undefined);

  const dataToView = useMemo(
    () => (spanId ? selectedSpanDataToView ?? trace : trace),
    [spanId, selectedSpanDataToView, trace],
  );

  return {
    dataToView,
    isSelectedSpanPending:
      shouldFetchSelectedSpan &&
      (isSelectedSpanFetching ||
        (isSelectedSpanPlaceholderData && !isSelectedSpanError)),
    selectedSpanData: selectedSpanFromListHasFullData
      ? selectedSpanFromList
      : validSelectedSpanData,
    selectedSpanFromList,
  };
}
