import React, { useCallback, useMemo } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { JsonParam, StringParam, useQueryParam } from "use-query-params";
import { ArrowUpRight, Loader2, MessagesSquare } from "lucide-react";
import last from "lodash/last";
import { Thread } from "@/types/traces";
import { Filter } from "@/types/filters";
import { useSMEFlow } from "../SMEFlowContext";
import useTracesList from "@/api/traces/useTracesList";
import TraceMessages from "@/v2/pages-shared/traces/TraceMessages/TraceMessages";
import { COLUMN_TYPE } from "@/types/shared";
import TraceDetailsPanel from "@/v2/pages-shared/traces/TraceDetailsPanel/TraceDetailsPanel";
import ThreadDetailsPanel from "@/v2/pages-shared/traces/ThreadDetailsPanel/ThreadDetailsPanel";
import { manageToolFilter } from "@/v2/pages-shared/traces/spanTypeFilter";
import TraceIdentifier from "./TraceIdentifier";
import { Button } from "@/ui/button";
import { formatThreadDateRange } from "@/lib/annotation-queues";

const MAX_THREAD_TRACES = 1000;
const STALE_TIME = 5 * 60 * 1000;

const useThreadData = () => {
  const { currentItem, nextDefaultItem } = useSMEFlow();

  const thread = currentItem as Thread;
  const nextThread = nextDefaultItem as Thread | undefined;

  const [traceId = "", setTraceId] = useQueryParam("trace", StringParam, {
    updateType: "replaceIn",
  });

  const [spanId = "", setSpanId] = useQueryParam("span", StringParam, {
    updateType: "replaceIn",
  });

  const [, setTracePanelFilters] = useQueryParam(
    `trace_panel_filters`,
    JsonParam,
    {
      updateType: "replaceIn",
    },
  );

  // Fetch current thread traces (not truncated)
  const { data: tracesData, isFetching } = useTracesList(
    {
      projectId: thread?.project_id || "",
      filters: [
        {
          id: "",
          field: "thread_id",
          type: COLUMN_TYPE.string,
          operator: "=",
          value: thread?.id || "",
        },
      ],
      page: 1,
      size: MAX_THREAD_TRACES,
      truncate: false,
    },
    {
      enabled: !!thread?.id,
      placeholderData: keepPreviousData,
      staleTime: STALE_TIME,
    },
  );

  // Preload next thread traces
  useTracesList(
    {
      projectId: nextThread?.project_id || "",
      filters: [
        {
          id: "",
          field: "thread_id",
          type: COLUMN_TYPE.string,
          operator: "=",
          value: nextThread?.id || "",
        },
      ],
      page: 1,
      size: MAX_THREAD_TRACES,
      truncate: false,
    },
    {
      enabled: !!nextThread?.id,
      placeholderData: keepPreviousData,
      staleTime: STALE_TIME,
    },
  );

  const traces = useMemo(
    () =>
      (tracesData?.content ?? []).sort((t1, t2) => t1.id.localeCompare(t2.id)),
    [tracesData],
  );

  const handleOpenTrace = useCallback(
    (id: string, shouldFilterToolCalls: boolean) => {
      setTracePanelFilters((previousFilters: Filter[] | null | undefined) =>
        manageToolFilter(previousFilters, shouldFilterToolCalls),
      );
      setTraceId(id);
      setSpanId("");
    },
    [setTracePanelFilters, setTraceId, setSpanId],
  );

  const handleCloseTrace = useCallback(() => {
    setTraceId("");
    setSpanId("");
  }, [setTraceId, setSpanId]);

  const [threadId = "", setThreadId] = useQueryParam("thread", StringParam, {
    updateType: "replaceIn",
  });

  const handleOpenThread = useCallback(() => {
    setThreadId(thread?.id || "");
  }, [thread?.id, setThreadId]);

  const handleCloseThread = useCallback(() => {
    setThreadId("");
  }, [setThreadId]);

  return {
    thread,
    traces,
    isFetching,
    traceId,
    setTraceId,
    spanId,
    setSpanId,
    handleOpenTrace,
    handleCloseTrace,
    threadId,
    handleOpenThread,
    handleCloseThread,
  };
};

const ThreadHeader: React.FC = () => {
  const { thread, isFetching, handleOpenThread } = useThreadData();

  const dateRange = formatThreadDateRange(thread?.start_time, thread?.end_time);

  return (
    <div className="flex h-10 shrink-0 items-center justify-between gap-2 border-b border-border bg-soft-background px-3">
      <div className="flex min-w-0 items-center gap-1.5">
        {isFetching && (
          <Loader2 className="size-3.5 shrink-0 animate-spin text-muted-slate" />
        )}
        <span className="comet-body-xs-accented shrink-0">Thread:</span>
        <div className="flex size-4 shrink-0 items-center justify-center rounded bg-[var(--thread-icon-background)] text-[var(--thread-icon-text)]">
          <MessagesSquare className="size-2" />
        </div>
        <TraceIdentifier name={dateRange} id={thread?.id || ""} />
      </div>
      <div className="flex shrink-0 items-center gap-1">
        <Button variant="ghost" size="2xs" onClick={handleOpenThread}>
          Thread
          <ArrowUpRight className="ml-1 size-3" />
        </Button>
      </div>
    </div>
  );
};

const ThreadContent: React.FC = () => {
  const {
    thread,
    traces,
    traceId,
    setTraceId,
    spanId,
    setSpanId,
    handleOpenTrace,
    handleCloseTrace,
    threadId,
    handleCloseThread,
  } = useThreadData();

  const { annotationQueue } = useSMEFlow();

  return (
    <>
      <TraceMessages
        traces={traces}
        traceId={last(traces)?.id}
        handleOpenTrace={handleOpenTrace}
      />
      <TraceDetailsPanel
        projectId={thread?.project_id || ""}
        traceId={traceId ?? ""}
        spanId={spanId ?? ""}
        setSpanId={setSpanId}
        open={Boolean(traceId)}
        onClose={handleCloseTrace}
        hideAnnotateActions
      />
      <ThreadDetailsPanel
        projectId={thread?.project_id || ""}
        projectName={annotationQueue?.project_name || ""}
        threadId={threadId ?? ""}
        traceId={traceId || undefined}
        setTraceId={setTraceId}
        open={Boolean(threadId)}
        onClose={handleCloseThread}
        hideAnnotateActions
      />
    </>
  );
};

const ThreadDataViewer = {
  Header: ThreadHeader,
  Content: ThreadContent,
};

export default ThreadDataViewer;
