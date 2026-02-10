import { useMemo } from "react";
import useTraceById from "@/api/traces/useTraceById";
import useThreadById from "@/api/traces/useThreadById";
import useTracesList from "@/api/traces/useTracesList";
import { ContextType, ContextData, EnrichedThread } from "@/types/custom-view";
import { COLUMN_TYPE } from "@/types/shared";

interface UseCustomViewDataParams {
  projectId: string;
  contextType: ContextType;
  traceId: string | null;
  threadId: string | null;
}

export function useCustomViewData(params: UseCustomViewDataParams) {
  const isTraceEnabled =
    params.contextType === "trace" && Boolean(params.traceId);
  const isThreadEnabled =
    params.contextType === "thread" && Boolean(params.threadId);

  const {
    data: traceData,
    isPending: isTracePending,
    isError: isTraceError,
  } = useTraceById(
    { traceId: params.traceId || "", stripAttachments: false },
    { enabled: isTraceEnabled },
  );

  const {
    data: threadData,
    isPending: isThreadPending,
    isError: isThreadError,
  } = useThreadById(
    { projectId: params.projectId, threadId: params.threadId || "" },
    { enabled: isThreadEnabled },
  );

  // Fetch all traces for the thread
  const {
    data: threadTracesData,
    isPending: isThreadTracesPending,
    isError: isThreadTracesError,
  } = useTracesList(
    {
      projectId: params.projectId,
      filters: [
        {
          id: "thread_filter",
          field: "thread_id",
          type: COLUMN_TYPE.string,
          operator: "=",
          value: params.threadId || "",
        },
      ],
      page: 1,
      size: 1000,
      truncate: false,
    },
    { enabled: isThreadEnabled },
  );

  // Sort traces by ID for chronological order and build enriched thread
  const enrichedThreadData = useMemo<EnrichedThread | null>(() => {
    if (!threadData) return null;
    const sortedTraces = (threadTracesData?.content ?? []).sort((t1, t2) =>
      t1.id.localeCompare(t2.id),
    );
    return { ...threadData, traces: sortedTraces };
  }, [threadData, threadTracesData]);

  const contextData = useMemo<ContextData | null | undefined>(() => {
    return params.contextType === "trace" ? traceData : enrichedThreadData;
  }, [params.contextType, traceData, enrichedThreadData]);

  // Only report loading when the query is enabled AND pending
  // Disabled queries have isPending=true but shouldn't show a loader
  // For threads, wait for both thread metadata AND traces to load
  const isLoading =
    params.contextType === "trace"
      ? isTraceEnabled && isTracePending
      : isThreadEnabled && (isThreadPending || isThreadTracesPending);
  const isError =
    params.contextType === "trace"
      ? isTraceError
      : isThreadError || isThreadTracesError;

  return {
    contextData,
    isLoading,
    isError,
    traceData,
    threadData,
  };
}
