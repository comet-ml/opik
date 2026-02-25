import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { keepPreviousData, useQueryClient } from "@tanstack/react-query";
import { BooleanParam, JsonParam, useQueryParam } from "use-query-params";
import find from "lodash/find";
import isBoolean from "lodash/isBoolean";
import isFunction from "lodash/isFunction";

import { OnChangeFn } from "@/types/shared";
import {
  ResizableHandle,
  ResizablePanel,
  ResizablePanelGroup,
} from "@/components/ui/resizable";
import useTraceById from "@/api/traces/useTraceById";
import Loader from "@/components/shared/Loader/Loader";
import TraceDataViewer from "./TraceDataViewer/TraceDataViewer";
import TraceTreeViewer from "./TraceTreeViewer/TraceTreeViewer";
import TraceAIViewer from "./TraceAIViewer/TraceAIViewer";
import TraceAnnotateViewer from "./TraceAnnotateViewer/TraceAnnotateViewer";
import NoData from "@/components/shared/NoData/NoData";
import { Span } from "@/types/traces";
import ResizableSidePanel from "@/components/shared/ResizableSidePanel/ResizableSidePanel";
import CommentsViewer from "./CommentsViewer/CommentsViewer";
import useLazySpansList from "@/api/traces/useLazySpansList";
import {
  DetailsActionSection,
  useDetailsActionSectionState,
} from "@/components/pages-shared/traces/DetailsActionSection";
import useTreeDetailsStore from "@/components/pages-shared/traces/TraceDetailsPanel/TreeDetailsStore";
import TraceDetailsActionsPanel from "@/components/pages-shared/traces/TraceDetailsPanel/TraceDetailsActionsPanel";
import get from "lodash/get";
import { METADATA_AGENT_GRAPH_KEY } from "@/constants/traces";
import api, { RUNNERS_REST_ENDPOINT, SPANS_KEY, TRACE_KEY } from "@/api/api";
import useCreateRunnerJobMutation from "@/api/runners/useCreateRunnerJobMutation";
import useMyRunner, { getStoredRunnerId } from "@/api/runners/useMyRunner";
import useDebugSession from "@/api/runners/useDebugSession";
import useDebugStep from "@/api/runners/useDebugStep";
import { RunnerJob } from "@/types/runners";

const MAX_SPANS_LOAD_SIZE = 15000;

type TraceDetailsPanelProps = {
  projectId?: string;
  traceId: string;
  spanId: string;
  setSpanId: OnChangeFn<string | null | undefined>;
  setThreadId?: OnChangeFn<string | null | undefined>;
  hasPreviousRow?: boolean;
  hasNextRow?: boolean;
  open: boolean;
  onClose: () => void;
  onRowChange?: (shift: number) => void;
};

const TraceDetailsPanel: React.FunctionComponent<TraceDetailsPanelProps> = ({
  projectId: externalProjectId,
  traceId,
  spanId,
  setSpanId,
  setThreadId,
  hasPreviousRow,
  hasNextRow,
  onClose,
  open,
  onRowChange,
}) => {
  const queryClient = useQueryClient();
  const [activeSection, setActiveSection] =
    useDetailsActionSectionState("lastSection");
  const { flattenedTree } = useTreeDetailsStore();

  const [graph = false, setGraph] = useQueryParam(
    `trace_panel_graph`,
    BooleanParam,
    {
      updateType: "replaceIn",
    },
  );

  const [search = undefined, setSearch] = useQueryParam(
    `trace_panel_search`,
    JsonParam,
    {
      updateType: "replaceIn",
    },
  );

  const [filters = [], setFilters] = useQueryParam(
    `trace_panel_filters`,
    JsonParam,
    {
      updateType: "replaceIn",
    },
  );

  const [debugSessionId, setDebugSessionId] = useState<string | null>(null);
  const { data: debugSession } = useDebugSession(debugSessionId, {
    refetchInterval: debugSessionId ? 1000 : false,
  });
  const debugStepMutation = useDebugStep();
  const prevCursorRef = useRef<number | undefined>();

  useEffect(() => {
    if (debugSession && debugSession.cursor !== prevCursorRef.current) {
      prevCursorRef.current = debugSession.cursor;
      queryClient.invalidateQueries({ queryKey: [TRACE_KEY] });
      queryClient.invalidateQueries({ queryKey: [SPANS_KEY] });
    }
  }, [debugSession?.cursor, queryClient]);

  const isDebugActive =
    debugSession != null && debugSession.status !== "completed";
  const effectiveTraceId =
    isDebugActive && debugSession.trace_id ? debugSession.trace_id : traceId;

  const { data: trace, isPending: isTracePending } = useTraceById(
    {
      traceId: effectiveTraceId,
      stripAttachments: true, // Keep attachments stripped - frontend fetches them separately
    },
    {
      placeholderData: keepPreviousData,
      enabled: Boolean(effectiveTraceId),
    },
  );

  const projectId = externalProjectId || trace?.project_id || "";

  const {
    query: { data: spansData, isPending: isSpansPending },
    isLazyLoading: isSpansLazyLoading,
  } = useLazySpansList(
    {
      traceId: effectiveTraceId,
      projectId,
      page: 1,
      size: MAX_SPANS_LOAD_SIZE,
      stripAttachments: true, // Keep attachments stripped - frontend fetches them separately
    },
    {
      placeholderData: keepPreviousData,
      enabled: Boolean(effectiveTraceId) && Boolean(projectId),
    },
  );

  const agentGraphData = get(
    trace,
    ["metadata", METADATA_AGENT_GRAPH_KEY],
    null,
  );
  const hasAgentGraph = Boolean(agentGraphData);
  const { data: runner } = useMyRunner({ refetchInterval: 5000 });
  const runnerId = getStoredRunnerId();
  const createDebugJobMutation = useCreateRunnerJobMutation();

  const handleRowSelect = useCallback(
    (id: string) => setSpanId(id === traceId ? "" : id),
    [setSpanId, traceId],
  );

  const dataToView = useMemo(() => {
    return spanId
      ? find(spansData?.content || [], (span: Span) => span.id === spanId) ??
          trace
      : trace;
  }, [spanId, spansData?.content, trace]);

  const treeData = useMemo(() => {
    return [...(trace ? [trace] : []), ...(spansData?.content || [])];
  }, [spansData?.content, trace]);

  const handleStartDebug = useCallback(
    (agentName: string) => {
      const agentInfo = runner?.agents?.find((a) => a.name === agentName);
      if (!agentInfo || !runnerId) return;
      createDebugJobMutation.mutate(
        {
          agent_name: agentName,
          inputs: treeData[0]?.input ?? {},
          project: agentInfo.project,
          runner_id: runnerId,
          debug: true,
        },
        {
          onSuccess: (job) => {
            const interval = setInterval(async () => {
              try {
                const { data } = await api.get<RunnerJob>(
                  `${RUNNERS_REST_ENDPOINT}jobs/${job.id}`,
                );
                if (data.debug_session_id) {
                  setDebugSessionId(data.debug_session_id);
                  clearInterval(interval);
                } else if (
                  data.status === "failed" ||
                  data.status === "completed"
                ) {
                  clearInterval(interval);
                }
              } catch {
                clearInterval(interval);
              }
            }, 1000);
          },
        },
      );
    },
    [runner, runnerId, createDebugJobMutation, treeData],
  );

  const handleDebugStepForward = useCallback(() => {
    if (!debugSessionId) return;
    debugStepMutation.mutate({ sessionId: debugSessionId, command: "step_forward" });
  }, [debugSessionId, debugStepMutation]);

  const handleDebugStepBack = useCallback(() => {
    if (!debugSessionId) return;
    debugStepMutation.mutate({ sessionId: debugSessionId, command: "step_back" });
  }, [debugSessionId, debugStepMutation]);

  const handleDebugRunToEnd = useCallback(() => {
    if (!debugSessionId) return;
    debugStepMutation.mutate({ sessionId: debugSessionId, command: "run_to_end" });
  }, [debugSessionId, debugStepMutation]);

  const handleDebugEnd = useCallback(() => {
    if (!debugSessionId) return;
    debugStepMutation.mutate(
      { sessionId: debugSessionId, command: "end" },
      { onSuccess: () => setDebugSessionId(null) },
    );
  }, [debugSessionId, debugStepMutation]);

  const horizontalNavigation = useMemo(
    () =>
      isBoolean(hasNextRow) &&
      isBoolean(hasPreviousRow) &&
      isFunction(onRowChange)
        ? {
            onChange: onRowChange,
            hasNext: hasNextRow,
            hasPrevious: hasPreviousRow,
          }
        : undefined,
    [hasNextRow, hasPreviousRow, onRowChange],
  );

  const verticalNavigation = useMemo(() => {
    const id = spanId || traceId;
    const index = flattenedTree.findIndex((node) => node.id === id);
    const nextRowId = index !== -1 ? flattenedTree[index + 1]?.id : undefined;
    const previousRowId = index > 0 ? flattenedTree[index - 1]?.id : undefined;

    return {
      onChange: (shift: 1 | -1) => {
        const rowId = shift > 0 ? nextRowId : previousRowId;
        rowId && handleRowSelect(rowId);
      },
      hasNext: Boolean(nextRowId),
      hasPrevious: Boolean(previousRowId),
      nextTooltip: "Next span",
      previousTooltip: "Previous span",
    };
  }, [spanId, traceId, handleRowSelect, flattenedTree]);

  const handleTraceDelete = useCallback(() => {
    // Navigate to previous/next trace before deleting, or close if it's the only trace
    if (hasPreviousRow && onRowChange) {
      onRowChange(-1);
    } else if (hasNextRow && onRowChange) {
      onRowChange(1);
    } else {
      onClose();
    }
  }, [hasPreviousRow, hasNextRow, onRowChange, onClose]);

  const renderContent = () => {
    if (isTracePending || isSpansPending) {
      return <Loader />;
    }

    if (!dataToView || !trace) {
      return <NoData />;
    }

    return (
      <div className="relative size-full">
        <ResizablePanelGroup direction="horizontal" autoSaveId="trace-sidebar">
          <ResizablePanel id="tree-viewer" defaultSize={40} minSize={20}>
            <TraceTreeViewer
              projectId={projectId}
              trace={trace}
              spans={spansData?.content}
              rowId={spanId || effectiveTraceId}
              onSelectRow={handleRowSelect}
              search={search}
              setSearch={setSearch}
              filters={filters}
              setFilters={setFilters}
              debugSession={isDebugActive ? debugSession : undefined}
              onDebugStepForward={handleDebugStepForward}
              onDebugStepBack={handleDebugStepBack}
              onDebugRunToEnd={handleDebugRunToEnd}
              onDebugEnd={handleDebugEnd}
            />
          </ResizablePanel>
          <ResizableHandle />
          <ResizablePanel id="data-viever" defaultSize={60} minSize={30}>
            <TraceDataViewer
              graphData={graph ? agentGraphData : undefined}
              data={dataToView}
              projectId={projectId}
              spanId={spanId}
              traceId={traceId}
              activeSection={activeSection}
              setActiveSection={setActiveSection}
              isSpansLazyLoading={isSpansLazyLoading}
              search={search}
              debugSession={isDebugActive ? debugSession : undefined}
              debugSessionId={debugSessionId}
            />
          </ResizablePanel>
          {Boolean(activeSection) && (
            <>
              <ResizableHandle />
              <ResizablePanel
                id="last-section-viewer"
                defaultSize={40}
                minSize={30}
              >
                {activeSection === DetailsActionSection.Annotations && (
                  <TraceAnnotateViewer
                    data={dataToView}
                    spanId={spanId}
                    traceId={traceId}
                    activeSection={activeSection}
                    setActiveSection={setActiveSection}
                  />
                )}
                {activeSection === DetailsActionSection.Comments && (
                  <CommentsViewer
                    data={dataToView}
                    spanId={spanId}
                    traceId={traceId}
                    projectId={projectId}
                    activeSection={activeSection}
                    setActiveSection={setActiveSection}
                  />
                )}
                {activeSection === DetailsActionSection.AIAssistants && (
                  <TraceAIViewer
                    traceId={traceId}
                    activeSection={activeSection}
                    setActiveSection={setActiveSection}
                    spans={spansData?.content}
                  />
                )}
              </ResizablePanel>
            </>
          )}
        </ResizablePanelGroup>
      </div>
    );
  };

  return (
    <ResizableSidePanel
      panelId="traces"
      entity="trace"
      open={open}
      headerContent={
        <TraceDetailsActionsPanel
          traceId={traceId}
          spanId={spanId}
          threadId={trace?.thread_id}
          setThreadId={setThreadId}
          projectId={projectId}
          onDelete={handleTraceDelete}
          isSpansLazyLoading={isSpansLazyLoading}
          search={search}
          setSearch={setSearch}
          filters={filters}
          setFilters={setFilters}
          treeData={treeData}
          graph={graph}
          setGraph={setGraph}
          hasAgentGraph={hasAgentGraph}
          setActiveSection={setActiveSection}
          onStartDebug={handleStartDebug}
        />
      }
      onClose={onClose}
      horizontalNavigation={horizontalNavigation}
      verticalNavigation={verticalNavigation}
      minWidth={700}
    >
      {renderContent()}
    </ResizableSidePanel>
  );
};

export default TraceDetailsPanel;
