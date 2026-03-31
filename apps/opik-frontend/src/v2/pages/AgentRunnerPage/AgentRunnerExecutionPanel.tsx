import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { ListTree } from "lucide-react";

import useTraceById from "@/api/traces/useTraceById";
import useSpansList from "@/api/traces/useSpansList";
import Loader from "@/shared/Loader/Loader";
import AgentTraceTree from "./AgentTraceTree";
import TraceDataViewer from "@/v2/pages-shared/traces/TraceDetailsPanel/TraceDataViewer/TraceDataViewer";
import { useDetailsActionSectionState } from "@/v2/pages-shared/traces/DetailsActionSection";
import { Span, Trace } from "@/types/traces";
import { SPANS_COLORS_MAP, TRACE_TYPE_FOR_TREE } from "@/constants/traces";
import useTreeDetailsStore, {
  TreeNode,
} from "@/v2/pages-shared/traces/TraceDetailsPanel/TreeDetailsStore";
import {
  ResizableHandle,
  ResizablePanel,
  ResizablePanelGroup,
} from "@/ui/resizable";
import find from "lodash/find";

const MAX_SPANS_LOAD_SIZE = 15000;
const SPANS_POLL_INTERVAL = 3000;

type AgentRunnerExecutionPanelProps = {
  traceId: string | null;
  projectId: string;
  isJobRunning: boolean;
  hasJob: boolean;
};

const buildTree = (trace: Trace, spans: Span[]): TreeNode[] => {
  const sharedData = {
    maxStartTime: new Date(trace.start_time).getTime(),
    maxEndTime: new Date(trace.end_time).getTime(),
    maxDuration: trace.duration,
  };

  const lookup: Record<string, TreeNode> = {
    [trace.id]: {
      id: trace.id,
      name: trace.name,
      data: {
        ...trace,
        ...sharedData,
        spanColor: SPANS_COLORS_MAP[TRACE_TYPE_FOR_TREE],
        parent_span_id: "",
        trace_id: trace.id,
        type: TRACE_TYPE_FOR_TREE,
        tokens: trace.usage?.total_tokens,
        duration: trace.duration,
        startTimestamp: new Date(trace.start_time).getTime(),
        name: trace.name,
        hasError: Boolean(trace.error_info),
      },
      children: [],
    },
  };

  const sortedSpans = [...spans]
    .filter((span) => span.trace_id === trace.id)
    .sort((s1, s2) => s1.start_time.localeCompare(s2.start_time));

  sortedSpans.forEach((span) => {
    lookup[span.id] = {
      id: span.id,
      name: span.name,
      data: {
        ...span,
        ...sharedData,
        spanColor: SPANS_COLORS_MAP[span.type],
        tokens: span.usage?.total_tokens,
        duration: span.duration,
        startTimestamp: new Date(span.start_time).getTime(),
        hasError: Boolean(span.error_info),
      },
      children: [],
    };
  });

  sortedSpans.forEach((span) => {
    const parentKey = span.parent_span_id;
    if (!parentKey) {
      lookup[trace.id].children?.push(lookup[span.id]);
    } else if (lookup[parentKey]) {
      lookup[parentKey].children?.push(lookup[span.id]);
    }
  });

  return [lookup[trace.id]];
};

const AgentRunnerExecutionPanel: React.FC<AgentRunnerExecutionPanelProps> = ({
  traceId,
  projectId,
  isJobRunning,
  hasJob,
}) => {
  const [selectedSpanId, setSelectedSpanId] = useState<string>("");
  const [activeSection, setActiveSection] = useDetailsActionSectionState(
    "agentSandboxSection",
  );
  const scrollRef = useRef<HTMLDivElement>(null);
  const setTree = useTreeDetailsStore((s) => s.setTree);

  useEffect(() => {
    setSelectedSpanId("");
  }, [traceId]);

  const { data: trace } = useTraceById(
    { traceId: traceId ?? "", stripAttachments: true },
    {
      placeholderData: keepPreviousData,
      enabled: Boolean(traceId),
      refetchInterval: isJobRunning ? SPANS_POLL_INTERVAL : false,
    },
  );

  const { data: spansData } = useSpansList(
    {
      traceId: traceId ?? "",
      projectId,
      page: 1,
      size: MAX_SPANS_LOAD_SIZE,
      stripAttachments: true,
    },
    {
      placeholderData: keepPreviousData,
      enabled: Boolean(traceId) && Boolean(projectId),
      refetchInterval: isJobRunning ? SPANS_POLL_INTERVAL : false,
    },
  );

  const spans = useMemo(() => spansData?.content ?? [], [spansData?.content]);

  useEffect(() => {
    if (!trace) {
      setTree([]);
      return;
    }
    setTree(buildTree(trace, spans));
  }, [trace, spans, setTree]);

  const dataToView = useMemo(() => {
    if (!trace) return null;
    if (selectedSpanId) {
      return find(spans, (span: Span) => span.id === selectedSpanId) ?? trace;
    }
    return trace;
  }, [selectedSpanId, spans, trace]);

  const handleSelectRow = useCallback(
    (id: string) => {
      setSelectedSpanId(id === traceId ? "" : id);
    },
    [traceId],
  );

  if (!hasJob) {
    return (
      <div className="flex h-full flex-col p-6">
        <span className="comet-body-s-accented mb-4">Trajectory</span>
        <div className="flex flex-1 flex-col items-center justify-center text-muted-slate">
          <ListTree className="mb-2 size-5" />
          <p className="comet-body-s font-medium">No run trace yet</p>
          <p className="comet-body-xs mt-1 text-center">
            Run your agent to see how it
            <br />
            executes step by step
          </p>
        </div>
      </div>
    );
  }

  if (isJobRunning && !trace) {
    return (
      <div className="flex h-full flex-col p-6">
        <span className="comet-body-s-accented mb-4">Trajectory</span>
        <div className="flex flex-1 flex-col items-center justify-center text-muted-slate">
          <Loader className="mb-2 size-5" />
          <p className="comet-body-s font-medium">Running agent...</p>
          <p className="comet-body-xs mt-1 text-center">
            Collecting trace data as your agent executes
          </p>
        </div>
      </div>
    );
  }

  if (!trace) {
    return (
      <div className="flex h-full flex-col p-6">
        <span className="comet-body-s-accented mb-4">Trajectory</span>
        <div className="flex flex-1 flex-col items-center justify-center text-muted-slate">
          <Loader className="mb-2 size-5" />
          <p className="comet-body-xs">Loading trace...</p>
        </div>
      </div>
    );
  }

  return (
    <div className="flex h-full flex-col">
      <ResizablePanelGroup
        direction="horizontal"
        autoSaveId="agent-sandbox-trajectory"
      >
        <ResizablePanel id="agent-tree" defaultSize={50} minSize={20}>
          <div className="size-full overflow-auto" ref={scrollRef}>
            <AgentTraceTree
              scrollRef={scrollRef}
              spanCount={spans.length}
              rowId={selectedSpanId || trace.id}
              onSelectRow={handleSelectRow}
              isJobRunning={isJobRunning}
            />
          </div>
        </ResizablePanel>
        <ResizableHandle />
        <ResizablePanel id="agent-data" defaultSize={50} minSize={20}>
          {dataToView && (
            <TraceDataViewer
              data={dataToView}
              projectId={projectId}
              spanId={selectedSpanId}
              traceId={trace.id}
              activeSection={activeSection}
              setActiveSection={setActiveSection}
              isSpansLazyLoading={false}
            />
          )}
        </ResizablePanel>
      </ResizablePanelGroup>
    </div>
  );
};

export default AgentRunnerExecutionPanel;
