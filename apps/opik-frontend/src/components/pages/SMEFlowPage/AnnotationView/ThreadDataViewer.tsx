import React, { useCallback, useMemo, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { JsonParam, useQueryParam } from "use-query-params";
import { Loader2 } from "lucide-react";
import last from "lodash/last";
import { Thread } from "@/types/traces";
import { useSMEFlow } from "../SMEFlowContext";
import useTracesList from "@/api/traces/useTracesList";
import TraceMessages from "@/components/pages-shared/traces/TraceMessages/TraceMessages";
import { COLUMN_TYPE } from "@/types/shared";
import TraceDetailsPanel from "@/components/pages-shared/traces/TraceDetailsPanel/TraceDetailsPanel";
import { createFilter } from "@/lib/filters";

const MAX_THREAD_TRACES = 1000;
const STALE_TIME = 5 * 60 * 1000; // 5 minutes

const ThreadDataViewer: React.FunctionComponent = () => {
  const { currentItem, nextItem } = useSMEFlow();

  const thread = currentItem as Thread;
  const nextThread = nextItem as Thread | undefined;

  const [traceId, setTraceId] = useState<string>("");
  const [spanId, setSpanId] = useState<string>("");

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const [_tracePanelFilters, setTracePanelFilters] = useQueryParam(
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
    (id: string, shouldFilterToolCalls?: boolean) => {
      setTraceId(id);
      setSpanId("");

      // Set filters if we need to filter tool calls
      if (shouldFilterToolCalls) {
        setTracePanelFilters([
          createFilter({
            id: "type",
            field: "type",
            operator: "=",
            value: "tool",
          }),
        ]);
      } else {
        setTracePanelFilters([]);
      }
    },
    [setTracePanelFilters],
  );

  const handleClose = useCallback(() => {
    setTraceId("");
    setSpanId("");
    setTracePanelFilters([]);
  }, [setTracePanelFilters]);

  const handleSetSpanId = useCallback(
    (
      updaterOrValue:
        | string
        | null
        | undefined
        | ((prev: string | null | undefined) => string | null | undefined),
    ) => {
      if (typeof updaterOrValue === "function") {
        setSpanId((prev) => {
          const newValue = updaterOrValue(prev);
          return newValue ?? "";
        });
      } else {
        setSpanId(updaterOrValue ?? "");
      }
    },
    [],
  );

  return (
    <>
      <div className="relative pr-4">
        {isFetching && (
          <div className="absolute right-6 top-2 z-10">
            <Loader2 className="size-4 animate-spin text-slate-400" />
          </div>
        )}
        <TraceMessages
          traces={traces}
          traceId={last(traces)?.id}
          handleOpenTrace={handleOpenTrace}
        />
      </div>
      <TraceDetailsPanel
        projectId={thread?.project_id || ""}
        traceId={traceId}
        spanId={spanId}
        setSpanId={handleSetSpanId}
        open={Boolean(traceId)}
        onClose={handleClose}
      />
    </>
  );
};

export default ThreadDataViewer;
