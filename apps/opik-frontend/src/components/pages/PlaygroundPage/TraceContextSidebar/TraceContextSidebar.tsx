import React, { useState, useMemo } from "react";
import {
  Brain,
  ChevronDown,
  ChevronRight,
  Clock,
  ExternalLink,
  Hash,
  PanelLeftClose,
  PanelLeftOpen,
  X,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import {
  PlaygroundTraceContext,
  TraceContextSpan,
} from "@/lib/playground/extractPlaygroundData";
import { SPANS_COLORS_MAP, TRACE_TYPE_FOR_TREE } from "@/constants/traces";
import { formatDuration } from "@/lib/date";
import BaseTraceDataTypeIcon from "@/components/pages-shared/traces/TraceDetailsPanel/BaseTraceDataTypeIcon";

interface TraceContextSidebarProps {
  traceContext: PlaygroundTraceContext;
  workspaceName: string;
  onClose: () => void;
  collapsed?: boolean;
  onToggleCollapse?: () => void;
}

interface SpanTreeNodeProps {
  span: TraceContextSpan;
  allSpans: TraceContextSpan[];
  level: number;
}

const SpanTreeNode: React.FC<SpanTreeNodeProps> = ({
  span,
  allSpans,
  level,
}) => {
  const [expanded, setExpanded] = useState(true);

  // Find children of this span
  const children = useMemo(() => {
    if (span.type === "trace") {
      // For trace, find spans with no parent or parent pointing to trace
      return allSpans.filter(
        (s) =>
          s.type !== "trace" && (!s.parentSpanId || s.parentSpanId === span.id),
      );
    }
    return allSpans.filter((s) => s.parentSpanId === span.id);
  }, [span, allSpans]);

  const hasChildren = children.length > 0;
  const spanColor =
    SPANS_COLORS_MAP[span.type] || SPANS_COLORS_MAP[TRACE_TYPE_FOR_TREE];

  return (
    <div className="flex flex-col">
      <div
        className={cn(
          "group flex items-center gap-2 rounded-md py-1.5 pr-2",
          span.isSource ? "bg-primary-100" : "hover:bg-muted",
        )}
        style={{ paddingLeft: `${level * 14 + 6}px` }}
      >
        {hasChildren ? (
          <button
            onClick={() => setExpanded(!expanded)}
            className="flex size-4 shrink-0 items-center justify-center rounded hover:bg-muted"
          >
            {expanded ? (
              <ChevronDown className="size-3 text-muted-foreground" />
            ) : (
              <ChevronRight className="size-3 text-muted-foreground" />
            )}
          </button>
        ) : (
          <span className="size-4 shrink-0" />
        )}

        <BaseTraceDataTypeIcon type={span.type} />

        <span
          className={cn(
            "comet-body-xs truncate",
            span.isSource ? "font-medium" : "text-muted-slate",
          )}
          title={span.name}
        >
          {span.name}
        </span>

        {span.isSource && (
          <span
            className="ml-auto shrink-0 rounded px-1.5 py-0.5 text-[10px] font-medium"
            style={{
              backgroundColor: spanColor,
              color: "white",
            }}
          >
            Source
          </span>
        )}
      </div>

      {hasChildren && expanded && (
        <div className="flex flex-col">
          {children.map((child) => (
            <SpanTreeNode
              key={child.id}
              span={child}
              allSpans={allSpans}
              level={level + 1}
            />
          ))}
        </div>
      )}
    </div>
  );
};

const TraceContextSidebar: React.FC<TraceContextSidebarProps> = ({
  traceContext,
  workspaceName,
  onClose,
  collapsed = false,
  onToggleCollapse,
}) => {
  // Build URL that opens the trace details panel directly
  // Format: /workspace/projects/projectId/traces?type=traces&trace=traceId&span=spanId
  const traceUrl = useMemo(() => {
    const baseUrl = `/${workspaceName}/projects/${traceContext.projectId}/traces`;
    const params = new URLSearchParams();
    params.set("type", "traces");
    params.set("trace", traceContext.traceId);
    if (traceContext.sourceSpan) {
      params.set("span", traceContext.sourceSpan.id);
    }
    return `${baseUrl}?${params.toString()}`;
  }, [workspaceName, traceContext]);

  // Find the root trace node
  const rootNode = useMemo(() => {
    return traceContext.traceStructure.find((s) => s.type === "trace");
  }, [traceContext.traceStructure]);

  if (collapsed) {
    return (
      <div className="flex h-full w-11 shrink-0 flex-col items-center border-r py-4">
        <TooltipWrapper content="Show trace context" side="right">
          <Button
            variant="ghost"
            size="icon-sm"
            onClick={onToggleCollapse}
            className="mb-4"
          >
            <PanelLeftOpen className="size-4" />
          </Button>
        </TooltipWrapper>
        <span
          className="comet-body-xs font-medium text-muted-foreground"
          style={{
            writingMode: "vertical-rl",
            textOrientation: "mixed",
            transform: "rotate(180deg)",
          }}
        >
          Trace Context
        </span>
      </div>
    );
  }

  return (
    <div className="flex h-full w-64 shrink-0 flex-col border-r">
      {/* Header */}
      <div className="flex h-11 items-center justify-between border-b px-4">
        <span className="comet-body-s-accented text-foreground-secondary">
          Trace Context
        </span>
        <div className="flex items-center gap-0.5">
          {onToggleCollapse && (
            <TooltipWrapper content="Collapse" side="bottom">
              <Button variant="ghost" size="icon-xs" onClick={onToggleCollapse}>
                <PanelLeftClose className="size-3.5" />
              </Button>
            </TooltipWrapper>
          )}
          <TooltipWrapper content="Close" side="bottom">
            <Button variant="ghost" size="icon-xs" onClick={onClose}>
              <X className="size-3.5" />
            </Button>
          </TooltipWrapper>
        </div>
      </div>

      {/* Trace Info */}
      <div className="border-b px-4 py-3">
        <div className="comet-body-xs mb-2 font-medium text-muted-slate">
          Source Trace
        </div>
        <div className="flex items-center gap-2">
          <BaseTraceDataTypeIcon type={TRACE_TYPE_FOR_TREE} />
          <span
            className="comet-body-s truncate font-medium"
            title={traceContext.traceName}
          >
            {traceContext.traceName}
          </span>
        </div>
      </div>

      {/* Source Span Info */}
      {traceContext.sourceSpan && (
        <div className="border-b px-4 py-3">
          <div className="comet-body-xs mb-2 font-medium text-muted-slate">
            Editing From
          </div>
          <div className="flex flex-col gap-2.5">
            <div className="flex items-center gap-2">
              <BaseTraceDataTypeIcon type={traceContext.sourceSpan.type} />
              <span
                className="comet-body-s truncate"
                title={traceContext.sourceSpan.name}
              >
                {traceContext.sourceSpan.name}
              </span>
            </div>
            <div className="flex flex-wrap items-center gap-x-4 gap-y-1.5">
              {traceContext.sourceSpan.model && (
                <TooltipWrapper content="Model" side="bottom">
                  <span className="comet-body-xs flex items-center gap-1.5 text-muted-slate">
                    <Brain className="size-3" />
                    {traceContext.sourceSpan.model}
                  </span>
                </TooltipWrapper>
              )}
              {traceContext.sourceSpan.duration !== undefined && (
                <TooltipWrapper content="Duration" side="bottom">
                  <span className="comet-body-xs flex items-center gap-1.5 text-muted-slate">
                    <Clock className="size-3" />
                    {formatDuration(traceContext.sourceSpan.duration)}
                  </span>
                </TooltipWrapper>
              )}
              {traceContext.sourceSpan.totalTokens !== undefined && (
                <TooltipWrapper content="Total tokens" side="bottom">
                  <span className="comet-body-xs flex items-center gap-1.5 text-muted-slate">
                    <Hash className="size-3" />
                    {traceContext.sourceSpan.totalTokens.toLocaleString()}
                  </span>
                </TooltipWrapper>
              )}
            </div>
          </div>
        </div>
      )}

      {/* Model Info */}
      {traceContext.originalModel && (
        <div className="border-b bg-warning-box-bg px-4 py-2.5">
          <div className="comet-body-xs text-warning-box-text">
            <span className="font-medium">Original model:</span>{" "}
            {traceContext.originalModel}
            {traceContext.originalProvider && (
              <span className="opacity-75">
                {" "}
                ({traceContext.originalProvider})
              </span>
            )}
          </div>
        </div>
      )}

      {/* Trace Tree */}
      <div className="flex min-h-0 flex-1 flex-col overflow-hidden">
        <div className="comet-body-xs px-4 py-2.5 font-medium text-muted-slate">
          Trace Structure
        </div>
        <div className="flex-1 overflow-y-auto px-3 pb-3">
          {rootNode && (
            <SpanTreeNode
              span={rootNode}
              allSpans={traceContext.traceStructure}
              level={0}
            />
          )}
        </div>
      </div>

      {/* Footer with link */}
      <div className="border-t px-4 py-3">
        <Button variant="outline" size="sm" className="w-full" asChild>
          <a href={traceUrl} target="_blank" rel="noopener noreferrer">
            <ExternalLink className="mr-1.5 size-3.5" />
            View Full Trace
          </a>
        </Button>
      </div>
    </div>
  );
};

export default TraceContextSidebar;
