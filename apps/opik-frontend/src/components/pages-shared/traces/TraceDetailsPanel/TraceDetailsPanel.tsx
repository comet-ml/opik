import React, { useCallback, useMemo } from "react";
import { keepPreviousData } from "@tanstack/react-query";
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

  const { data: trace, isPending: isTracePending } = useTraceById(
    {
      traceId,
      stripAttachments: true, // Keep attachments stripped - frontend fetches them separately
    },
    {
      placeholderData: keepPreviousData,
      enabled: Boolean(traceId),
    },
  );

  const projectId = externalProjectId || trace?.project_id || "";

  const {
    query: { data: spansData, isPending: isSpansPending },
    isLazyLoading: isSpansLazyLoading,
  } = useLazySpansList(
    {
      traceId,
      projectId,
      page: 1,
      size: MAX_SPANS_LOAD_SIZE,
      stripAttachments: true, // Keep attachments stripped - frontend fetches them separately
    },
    {
      placeholderData: keepPreviousData,
      enabled: Boolean(traceId) && Boolean(projectId),
    },
  );

  const agentGraphData = get(
    trace,
    ["metadata", METADATA_AGENT_GRAPH_KEY],
    null,
  );
  const hasAgentGraph = Boolean(agentGraphData);

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
              rowId={spanId || traceId}
              onSelectRow={handleRowSelect}
              search={search}
              setSearch={setSearch}
              filters={filters}
              setFilters={setFilters}
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
          onClose={onClose}
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
