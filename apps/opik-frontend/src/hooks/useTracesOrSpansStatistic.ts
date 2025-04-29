import {
  keepPreviousData,
  QueryObserverResult,
  RefetchOptions,
  UseQueryOptions,
} from "@tanstack/react-query";
import { SPAN_TYPE } from "@/types/traces";
import { Filters } from "@/types/filters";
import { ColumnsStatistic } from "@/types/shared";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import useTracesStatistic from "@/api/traces/useTracesStatistic";
import useSpansStatistic from "@/api/traces/useSpansStatistic";

type UseTracesOrSpansStatisticParams = {
  projectId: string;
  type: TRACE_DATA_TYPE;
  filters?: Filters;
  search?: string;
};

type UseTracesOrSpansStatisticResponse = {
  data: {
    stats: ColumnsStatistic;
  };
  isPending: boolean;
  isLoading: boolean;
  isError: boolean;
  refetch: (
    options?: RefetchOptions,
  ) => Promise<QueryObserverResult<unknown, unknown>>;
};

export default function useTracesOrSpansStatistic(
  params: UseTracesOrSpansStatisticParams,
  config: Omit<UseQueryOptions, "queryKey" | "queryFn">,
) {
  const isTracesData = params.type === TRACE_DATA_TYPE.traces;

  const {
    data: tracesData,
    isError: isTracesError,
    isPending: isTracesPending,
    isLoading: isTracesLoading,
    refetch: refetchTrace,
  } = useTracesStatistic(params, {
    ...config,
    enabled: isTracesData,
    placeholderData: keepPreviousData,
  } as never);

  const {
    data: spansData,
    isError: isSpansError,
    isPending: isSpansPending,
    isLoading: isSpansLoading,
    refetch: refetchSpan,
  } = useSpansStatistic(
    {
      ...params,
      type: SPAN_TYPE.llm,
    },
    {
      ...config,
      enabled: !isTracesData,
      placeholderData: keepPreviousData,
    } as never,
  );

  const data = !isTracesData ? spansData : tracesData;

  const isError = !isTracesData ? isSpansError : isTracesError;
  const isPending = !isTracesData ? isSpansPending : isTracesPending;
  const isLoading = !isTracesData ? isSpansLoading : isTracesLoading;
  const refetch = !isTracesData ? refetchSpan : refetchTrace;

  return {
    refetch,
    data,
    isError,
    isPending,
    isLoading,
  } as UseTracesOrSpansStatisticResponse;
}
