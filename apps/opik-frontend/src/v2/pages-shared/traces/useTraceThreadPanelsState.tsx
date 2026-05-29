import React, { useCallback, useMemo } from "react";
import { StringParam, useQueryParam } from "use-query-params";
import findIndex from "lodash/findIndex";

import TraceDetailsPanel, {
  TraceDetailsPanelProps,
} from "@/v2/pages-shared/traces/TraceDetailsPanel/TraceDetailsPanel";
import ThreadDetailsPanel, {
  ThreadDetailsPanelProps,
} from "@/v2/pages-shared/traces/ThreadDetailsPanel/ThreadDetailsPanel";
import {
  DetailsActionSectionParam,
  DetailsActionSectionValue,
} from "@/v2/pages-shared/traces/DetailsActionSection";

type UseTraceThreadPanelsStateOpts<TRow extends { id: string }> = {
  rows: TRow[];
  type: "trace" | "thread";
  traceDetailsPanelProps: Partial<TraceDetailsPanelProps>;
  threadDetailsPanelProps?: Partial<ThreadDetailsPanelProps> &
    Pick<ThreadDetailsPanelProps, "projectId" | "projectName">;
  queryPrefix?: string;
  manageLastSection?: boolean;
};

const useTraceThreadPanelsState = <TRow extends { id: string }>({
  rows,
  type,
  traceDetailsPanelProps,
  threadDetailsPanelProps,
  queryPrefix = "",
  manageLastSection = false,
}: UseTraceThreadPanelsStateOpts<TRow>) => {
  const [traceId = "", setTraceId] = useQueryParam(
    `${queryPrefix}trace`,
    StringParam,
    { updateType: "replaceIn" },
  );

  const [spanId = "", setSpanId] = useQueryParam(
    `${queryPrefix}span`,
    StringParam,
    { updateType: "replaceIn" },
  );

  const [threadId = "", setThreadId] = useQueryParam(
    `${queryPrefix}thread`,
    StringParam,
    { updateType: "replaceIn" },
  );

  const [, setLastSection] = useQueryParam(
    `${queryPrefix}lastSection`,
    DetailsActionSectionParam,
    { updateType: "replaceIn" },
  );

  const handleRowClick = useCallback(
    (row?: TRow, lastSection?: DetailsActionSectionValue) => {
      if (!row) return;

      if (type === "trace") {
        setTraceId((state) => (row.id === state ? "" : row.id));
        setSpanId("");
      } else {
        setThreadId((state) => (row.id === state ? "" : row.id));
      }

      if (manageLastSection && lastSection) {
        setLastSection(lastSection);
      }
    },
    [
      type,
      manageLastSection,
      setTraceId,
      setSpanId,
      setThreadId,
      setLastSection,
    ],
  );

  const handleThreadIdClick = useCallback(
    (row?: { id: string; thread_id?: string }) => {
      if (!row || !row.thread_id) return;
      setThreadId(row.thread_id);
      setTraceId(row.id);
    },
    [setThreadId, setTraceId],
  );

  const activeRowId = type === "trace" ? traceId ?? "" : threadId ?? "";

  const rowIndex = useMemo(
    () => findIndex(rows, (row) => row.id === activeRowId),
    [rows, activeRowId],
  );

  const hasNext = rowIndex >= 0 ? rowIndex < rows.length - 1 : false;
  const hasPrevious = rowIndex >= 0 ? rowIndex > 0 : false;

  const handleRowChange = useCallback(
    (shift: number) => handleRowClick(rows[rowIndex + shift]),
    [handleRowClick, rowIndex, rows],
  );

  const handleClose = useCallback(() => {
    setTraceId("");
    setSpanId("");
    setThreadId("");
  }, [setTraceId, setSpanId, setThreadId]);

  const renderThreadPanel = Boolean(threadDetailsPanelProps);

  const panels = (
    <>
      <TraceDetailsPanel
        {...traceDetailsPanelProps}
        traceId={traceId ?? ""}
        spanId={spanId ?? ""}
        setSpanId={setSpanId}
        setThreadId={renderThreadPanel ? setThreadId : undefined}
        hasPreviousRow={type === "trace" ? hasPrevious : undefined}
        hasNextRow={type === "trace" ? hasNext : undefined}
        onRowChange={type === "trace" ? handleRowChange : undefined}
        open={
          renderThreadPanel ? Boolean(traceId) && !threadId : Boolean(traceId)
        }
        onClose={handleClose}
      />
      {threadDetailsPanelProps && (
        <ThreadDetailsPanel
          {...threadDetailsPanelProps}
          traceId={traceId ?? ""}
          threadId={threadId ?? ""}
          setTraceId={setTraceId}
          hasPreviousRow={type === "thread" ? hasPrevious : undefined}
          hasNextRow={type === "thread" ? hasNext : undefined}
          onRowChange={type === "thread" ? handleRowChange : undefined}
          open={Boolean(threadId)}
          onClose={handleClose}
        />
      )}
    </>
  );

  return {
    traceId: traceId ?? "",
    spanId: spanId ?? "",
    threadId: threadId ?? "",
    setTraceId,
    setSpanId,
    setThreadId,
    activeRowId,
    hasNext,
    hasPrevious,
    handleRowClick,
    handleThreadIdClick,
    handleRowChange,
    handleClose,
    panels,
  };
};

export default useTraceThreadPanelsState;
