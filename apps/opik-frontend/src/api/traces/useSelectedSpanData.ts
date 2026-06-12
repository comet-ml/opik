import { useMemo } from "react";
import find from "lodash/find";

import { QueryConfig } from "@/api/api";
import useSpanById from "@/api/traces/useSpanById";
import { Span, Trace } from "@/types/traces";

type UseSelectedSpanDataParams = {
  spanId: string;
  traceId: string;
  spans?: Span[];
  trace?: Trace;
  stripAttachments?: boolean;
};

const hasFullSpanData = (span?: Span) =>
  Boolean(
    span &&
      span.input !== null &&
      span.input !== undefined &&
      span.output !== null &&
      span.output !== undefined,
  );

export default function useSelectedSpanData(
  {
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
        (span: Span) => span.id === spanId && span.trace_id === traceId,
      ),
    [spanId, spans, traceId],
  );
  const shouldFetchSelectedSpan =
    Boolean(spanId) && !hasFullSpanData(selectedSpanFromList);

  const {
    data: selectedSpanData,
    isError: isSelectedSpanError,
    isFetching: isSelectedSpanFetching,
    isPlaceholderData: isSelectedSpanPlaceholderData,
  } = useSpanById(
    {
      spanId,
      stripAttachments,
    },
    {
      ...options,
      placeholderData: selectedSpanFromList,
      enabled: shouldFetchSelectedSpan && (options?.enabled ?? true),
    },
  );

  const validSelectedSpanData =
    selectedSpanData?.trace_id === traceId ? selectedSpanData : undefined;
  const selectedSpanFromListHasFullData = hasFullSpanData(selectedSpanFromList);
  const selectedSpanDataToView = selectedSpanFromListHasFullData
    ? selectedSpanFromList
    : validSelectedSpanData ?? selectedSpanFromList;

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
