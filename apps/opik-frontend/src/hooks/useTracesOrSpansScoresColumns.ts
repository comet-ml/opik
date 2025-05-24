import { keepPreviousData, UseQueryOptions } from "@tanstack/react-query";
import useTracesFeedbackScoresNames from "@/api/traces/useTracesFeedbackScoresNames";
import useSpansFeedbackScoresNames from "@/api/traces/useSpansFeedbackScoresNames";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import { SPAN_TYPE } from "@/types/traces";
import { FeedbackScoreName } from "@/types/shared";

type UseTracesOrSpansScoresColumnsParams = {
  projectId: string;
  type: TRACE_DATA_TYPE;
  spanType?: SPAN_TYPE;
};

type UseTracesOrSpansScoresColumnsResponse = {
  data: {
    scores: FeedbackScoreName[];
    total: number;
  };
  isPending: boolean;
};

export default function useTracesOrSpansScoresColumns(
  params: UseTracesOrSpansScoresColumnsParams,
  config: Omit<UseQueryOptions, "queryKey" | "queryFn">,
) {
  const isTracesData = params.type === TRACE_DATA_TYPE.traces;

  const { data: tracesData, isPending: isTracesPending } =
    useTracesFeedbackScoresNames(params, {
      ...config,
      enabled: isTracesData,
      placeholderData: keepPreviousData,
    } as never);

  const { data: spansData, isPending: isSpansPending } =
    useSpansFeedbackScoresNames(
      {
        ...params,
        type:
          !isTracesData && params.spanType ? params.spanType : SPAN_TYPE.llm,
      },
      {
        ...config,
        enabled: !isTracesData,
        placeholderData: keepPreviousData,
      } as never,
    );

  return {
    data: !isTracesData ? spansData : tracesData,
    isPending: !isTracesData ? isSpansPending : isTracesPending,
  } as UseTracesOrSpansScoresColumnsResponse;
}
