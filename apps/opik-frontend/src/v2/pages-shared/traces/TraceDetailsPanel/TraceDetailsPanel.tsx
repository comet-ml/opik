import React, { useCallback, useMemo, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { BooleanParam, JsonParam, useQueryParam } from "use-query-params";
import find from "lodash/find";
import isBoolean from "lodash/isBoolean";
import isFunction from "lodash/isFunction";
import useLocalStorageState from "use-local-storage-state";

import { OnChangeFn } from "@/types/shared";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/ui/dialog";
import ZoomPanContainer from "@/shared/ZoomPanContainer/ZoomPanContainer";
import MermaidDiagram from "@/shared/MermaidDiagram/MermaidDiagram";
import {
  ResizableHandle,
  ResizablePanel,
  ResizablePanelGroup,
} from "@/ui/resizable";
import useTraceById from "@/api/traces/useTraceById";
import Loader from "@/shared/Loader/Loader";
import TraceDataViewer from "./TraceDataViewer/TraceDataViewer";
import TraceTreeViewer from "./TraceTreeViewer/TraceTreeViewer";
import TraceAIViewer from "./TraceAIViewer/TraceAIViewer";
import AnnotatePanel from "./AnnotatePanel/AnnotatePanel";
import AgentGraphHeader from "./AgentGraphHeader";
import AgentGraphTab from "./TraceDataViewer/AgentGraphTab";
import NoData from "@/shared/NoData/NoData";
import { BASE_TRACE_DATA_TYPE, Span } from "@/types/traces";
import ResizableSidePanel from "@/shared/ResizableSidePanel/ResizableSidePanel";
import useLazySpansList from "@/api/traces/useLazySpansList";
import {
  DetailsActionSection,
  useDetailsActionSectionState,
} from "@/v2/pages-shared/traces/DetailsActionSection";
import useTreeDetailsStore from "@/v2/pages-shared/traces/TraceDetailsPanel/TreeDetailsStore";
import TraceDetailsActionsPanel from "@/v2/pages-shared/traces/TraceDetailsPanel/TraceDetailsActionsPanel";
import {
  TraceTreeToolbar,
  TraceDataToolbar,
} from "@/v2/pages-shared/traces/TraceDetailsPanel/TraceDetailsToolbar";
import {
  SELECTED_TREE_DATABLOCKS_KEY,
  SELECTED_TREE_DATABLOCKS_DEFAULT_VALUE,
} from "@/v2/pages-shared/traces/TraceDetailsPanel/treeConfig";
import get from "lodash/get";
import {
  METADATA_AGENT_GRAPH_KEY,
  TRACE_TYPE_FOR_TREE,
} from "@/constants/traces";
import { usePermissions } from "@/contexts/PermissionsContext";

const MAX_SPANS_LOAD_SIZE = 15000;
const EMPTY_FILTERS: unknown[] = [];

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
  container?: HTMLElement | null;
  refetchInterval?: number | false;
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
  container,
  refetchInterval,
}) => {
  const [activeSection, setActiveSection] =
    useDetailsActionSectionState("lastSection");
  const { flattenedTree } = useTreeDetailsStore();
  const [isGraphCollapsed = false, setIsGraphCollapsed] = useQueryParam(
    `trace_panel_graph_collapsed`,
    BooleanParam,
    { updateType: "replaceIn" },
  );
  const [isGraphFullscreen, setIsGraphFullscreen] = useState(false);

  const [search = undefined, setSearch] = useQueryParam(
    `trace_panel_search`,
    JsonParam,
    {
      updateType: "replaceIn",
    },
  );

  const [filters = EMPTY_FILTERS, setFilters] = useQueryParam(
    `trace_panel_filters`,
    JsonParam,
    {
      updateType: "replaceIn",
    },
  );

  const [treeConfig, setTreeConfig] = useLocalStorageState(
    SELECTED_TREE_DATABLOCKS_KEY,
    {
      defaultValue: SELECTED_TREE_DATABLOCKS_DEFAULT_VALUE,
    },
  );

  const {
    permissions: { canAnnotateTraceSpanThread },
  } = usePermissions();

  const { data: trace, isPending: isTracePending } = useTraceById(
    {
      traceId,
      stripAttachments: true,
    },
    {
      placeholderData: keepPreviousData,
      enabled: Boolean(traceId),
      refetchInterval,
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
      stripAttachments: true,
    },
    {
      placeholderData: keepPreviousData,
      enabled: Boolean(traceId) && Boolean(projectId),
      refetchInterval,
    },
  );

  const agentGraphData = get(
    trace,
    ["metadata", METADATA_AGENT_GRAPH_KEY],
    null,
  );
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

  const spanCount = spansData?.content?.length ?? 0;

  const traceType: BASE_TRACE_DATA_TYPE | undefined = trace
    ? TRACE_TYPE_FOR_TREE
    : undefined;

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

    const treeViewer = (
      <TraceTreeViewer
        trace={trace}
        spans={spansData?.content}
        rowId={spanId || traceId}
        onSelectRow={handleRowSelect}
        search={search}
        filters={filters}
        config={treeConfig}
      />
    );

    const isAnnotating =
      activeSection === DetailsActionSection.Annotate ||
      activeSection === DetailsActionSection.Annotations ||
      activeSection === DetailsActionSection.Comments;

    return (
      <div className="relative size-full">
        <ResizablePanelGroup direction="horizontal" autoSaveId="trace-sidebar">
          <ResizablePanel id="tree-viewer" defaultSize={40} minSize={20}>
            <div className="flex size-full flex-col">
              <TraceTreeToolbar
                spanCount={spanCount}
                search={search}
                setSearch={setSearch}
                filters={filters}
                setFilters={setFilters}
                isSpansLazyLoading={isSpansLazyLoading}
                treeData={treeData}
                config={treeConfig}
                setConfig={setTreeConfig}
              />
              {agentGraphData && !isGraphCollapsed ? (
                <ResizablePanelGroup
                  direction="vertical"
                  autoSaveId="trace-tree-graph"
                  className="flex-auto"
                >
                  <ResizablePanel
                    id="tree-content"
                    defaultSize={60}
                    minSize={20}
                  >
                    <div className="size-full overflow-hidden">
                      {treeViewer}
                    </div>
                  </ResizablePanel>
                  <ResizableHandle />
                  <ResizablePanel
                    id="agent-graph"
                    defaultSize={40}
                    minSize={15}
                  >
                    <div className="flex size-full flex-col overflow-hidden">
                      <AgentGraphHeader
                        isCollapsed={false}
                        onToggleCollapse={() => setIsGraphCollapsed(true)}
                        onFullscreen={() => setIsGraphFullscreen(true)}
                        border="bottom"
                      />
                      <div className="flex-auto overflow-hidden p-2">
                        <AgentGraphTab data={agentGraphData} />
                      </div>
                    </div>
                  </ResizablePanel>
                </ResizablePanelGroup>
              ) : (
                <>
                  <div className="relative flex-auto overflow-hidden">
                    {treeViewer}
                  </div>
                  {agentGraphData && (
                    <AgentGraphHeader
                      isCollapsed
                      onToggleCollapse={() => setIsGraphCollapsed(false)}
                      onFullscreen={() => setIsGraphFullscreen(true)}
                      border="top"
                    />
                  )}
                </>
              )}
            </div>
          </ResizablePanel>
          <ResizableHandle />
          <ResizablePanel id="data-viewer" defaultSize={60} minSize={30}>
            <div className="flex size-full flex-col">
              <TraceDataToolbar
                dataToView={dataToView}
                setActiveSection={setActiveSection}
              />
              <div className="relative flex-auto overflow-hidden">
                <TraceDataViewer
                  data={dataToView}
                  projectId={projectId}
                  spanId={spanId}
                  traceId={traceId}
                  setActiveSection={setActiveSection}
                  isSpansLazyLoading={isSpansLazyLoading}
                  search={search}
                />
              </div>
            </div>
          </ResizablePanel>
          {Boolean(activeSection) &&
            (canAnnotateTraceSpanThread || !isAnnotating) && (
              <>
                <ResizableHandle />
                <ResizablePanel
                  id="last-section-viewer"
                  defaultSize={40}
                  minSize={30}
                >
                  {isAnnotating && (
                    <AnnotatePanel
                      data={dataToView}
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
        {agentGraphData && (
          <Dialog open={isGraphFullscreen} onOpenChange={setIsGraphFullscreen}>
            <DialogContent
              className="size-full max-h-[90vh] max-w-[90vw]"
              onEscapeKeyDown={(e) => e.stopPropagation()}
              onOpenAutoFocus={(e) => e.preventDefault()}
            >
              <DialogHeader>
                <DialogTitle>Agent graph</DialogTitle>
              </DialogHeader>
              <div className="flex-auto overflow-hidden">
                <ZoomPanContainer expandButton={false}>
                  <MermaidDiagram chart={agentGraphData.data} />
                </ZoomPanContainer>
              </div>
            </DialogContent>
          </Dialog>
        )}
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
          traceName={trace?.name}
          traceType={traceType}
          threadId={trace?.thread_id}
          setThreadId={setThreadId}
          projectId={projectId}
          onDelete={handleTraceDelete}
          onClose={onClose}
          treeData={treeData}
          setActiveSection={setActiveSection}
          horizontalNavigation={horizontalNavigation}
        />
      }
      onClose={onClose}
      hideDefaultControls
      ignoreHotkeys={isGraphFullscreen}
      horizontalNavigation={horizontalNavigation}
      verticalNavigation={verticalNavigation}
      minWidth={700}
      container={container}
    >
      {renderContent()}
    </ResizableSidePanel>
  );
};

export default TraceDetailsPanel;
