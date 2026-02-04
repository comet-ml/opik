import {
  keepPreviousData,
  QueryObserverResult,
  RefetchOptions,
  UseQueryOptions,
} from "@tanstack/react-query";

import isBoolean from "lodash/isBoolean";

import useTracesList from "@/api/traces/useTracesList";
import useSpansList from "@/api/traces/useSpansList";
import { Span, Trace } from "@/types/traces";
import { Filters } from "@/types/filters";
import { Sorting } from "@/types/sorting";
import { TRACE_DATA_TYPE } from "@/constants/traces";

export { TRACE_DATA_TYPE };

type UseTracesOrSpansListParams = {
  projectId: string;
  type: TRACE_DATA_TYPE;
  filters?: Filters;
  sorting?: Sorting;
  search?: string;
  page: number;
  size: number;
  truncate?: boolean;
  fromTime?: string;
  toTime?: string;
  exclude?: string[];
};

export type TracesOrSpansListData = {
  content: Array<Trace | Span>;
  sortable_by: string[];
  total: number;
};

type UseTracesOrSpansListResponse = {
  data: TracesOrSpansListData | undefined;
  isPending: boolean;
  isLoading: boolean;
  isError: boolean;
  isPlaceholderData: boolean;
  isFetching: boolean;
  refetch: (
    options?: RefetchOptions,
  ) => Promise<QueryObserverResult<TracesOrSpansListData, unknown>>;
};

export default function useTracesOrSpansList(
  params: UseTracesOrSpansListParams,
  config: Omit<UseQueryOptions, "queryKey" | "queryFn">,
) {
  const isTracesData = params.type === TRACE_DATA_TYPE.traces;
  const isEnabled = isBoolean(config.enabled) ? config.enabled : true;

  const {
    data: tracesData,
    isError: isTracesError,
    isPending: isTracesPending,
    isLoading: isTracesLoading,
    isPlaceholderData: isTracesPlaceholderData,
    isFetching: isTracesFetching,
    refetch: refetchTrace,
  } = useTracesList(params, {
    ...config,
    enabled: isTracesData && isEnabled,
    placeholderData: keepPreviousData,
  } as never);

  const {
    data: spansData,
    isError: isSpansError,
    isPending: isSpansPending,
    isLoading: isSpansLoading,
    isPlaceholderData: isSpansPlaceholderData,
    isFetching: isSpansFetching,
    refetch: refetchSpan,
  } = useSpansList(
    {
      ...params,
      type: undefined,
    },
    {
      ...config,
      enabled: !isTracesData && isEnabled,
      placeholderData: keepPreviousData,
    } as never,
  );

  const data = !isTracesData ? spansData : tracesData;
  const isError = !isTracesData ? isSpansError : isTracesError;
  const isPending = !isTracesData ? isSpansPending : isTracesPending;
  const isLoading = !isTracesData ? isSpansLoading : isTracesLoading;
  const isPlaceholderData = !isTracesData
    ? isSpansPlaceholderData
    : isTracesPlaceholderData;
  const isFetching = !isTracesData ? isSpansFetching : isTracesFetching;
  const refetch = !isTracesData ? refetchSpan : refetchTrace;

  return {
    refetch,
    data,
    isError,
    isPending,
    isLoading,
    isPlaceholderData,
    isFetching,
  } as UseTracesOrSpansListResponse;
}
