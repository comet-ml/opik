import { keepPreviousData } from "@tanstack/react-query";

import shouldLoadFullSpansData from "@/api/traces/shouldLoadFullSpansData";
import useLazySpansList from "@/api/traces/useLazySpansList";
import useSelectedSpanData from "@/api/traces/useSelectedSpanData";
import { MAX_SPANS_FULL_DATA_LOAD_SIZE } from "@/constants/traces";
import { Trace } from "@/types/traces";

const MAX_SPANS_LOAD_SIZE = 15000;

type UseTraceDetailsSpansLoaderParams = {
  externalProjectId?: string;
  traceId: string;
  spanId: string;
  trace?: Trace;
  search?: unknown;
  filters?: unknown;
  refetchInterval?: number | false;
};

export default function useTraceDetailsSpansLoader({
  externalProjectId,
  traceId,
  spanId,
  trace,
  search,
  filters,
  refetchInterval,
}: UseTraceDetailsSpansLoaderParams) {
  const projectId = externalProjectId || trace?.project_id || "";
  const loadFullSpansData = shouldLoadFullSpansData(search, filters);
  const queryOptions = {
    placeholderData: keepPreviousData,
    enabled: Boolean(traceId) && Boolean(projectId),
    ...(refetchInterval !== undefined && { refetchInterval }),
  };
  const selectedSpanOptions =
    refetchInterval !== undefined ? { refetchInterval } : undefined;

  const {
    query: { data: spansData, isPending: isSpansPending },
    isLazyLoading: isSpansLazyLoading,
  } = useLazySpansList(
    {
      traceId,
      projectId,
      page: 1,
      size: MAX_SPANS_LOAD_SIZE,
      stripAttachments: true,
    },
    queryOptions,
    {
      maxFullDataSpans: MAX_SPANS_FULL_DATA_LOAD_SIZE,
      loadFullData: loadFullSpansData,
    },
  );

  const { dataToView, isSelectedSpanPending } = useSelectedSpanData(
    {
      spanId,
      traceId,
      spans: spansData?.content,
      trace,
      stripAttachments: true,
    },
    selectedSpanOptions,
  );

  return {
    projectId,
    spansData,
    isSpansPending,
    isSpansLazyLoading,
    dataToView,
    isSelectedSpanPending,
  };
}
