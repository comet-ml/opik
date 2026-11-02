import React, { useCallback, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { JsonParam, StringParam, useQueryParam } from "use-query-params";
import { ArrowUpRight, Loader2 } from "lucide-react";
import BaseTraceDataTypeIcon from "@/shared/BaseTraceDataTypeIcon/BaseTraceDataTypeIcon";
import { TRACE_TYPE_FOR_TREE } from "@/constants/traces";
import { Trace } from "@/types/traces";
import { Filter } from "@/types/filters";
import { useSMEFlow } from "../SMEFlowContext";
import useTraceById from "@/api/traces/useTraceById";
import TraceDetailsPanel from "@/v2/pages-shared/traces/TraceDetailsPanel/TraceDetailsPanel";
import DefaultTraceView from "@/v2/pages-shared/annotation-queues/AnnotatorView/DefaultTraceView";
import AnnotatorViewFallbackAlert from "@/v2/pages-shared/annotation-queues/AnnotatorView/AnnotatorViewFallbackAlert";
import SandboxedAnnotatorTraceView from "@/v2/pages-shared/annotation-queues/AnnotatorView/SandboxedAnnotatorTraceView";
import { LATEST_KIT_VERSION } from "@/shared/SandboxedView/kit/loadKit";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";
import { manageToolFilter } from "@/v2/pages-shared/traces/spanTypeFilter";
import TraceIdentifier from "./TraceIdentifier";
import { Button } from "@/ui/button";

const STALE_TIME = 5 * 60 * 1000;

const useTraceData = () => {
  const { currentItem, nextDefaultItem } = useSMEFlow();

  const trace = currentItem as Trace;
  const nextTrace = nextDefaultItem as Trace | undefined;

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

  const { data: fullTrace, isLoading } = useTraceById(
    { traceId: trace?.id || "" },
    {
      enabled: !!trace?.id,
      placeholderData: keepPreviousData,
      staleTime: STALE_TIME,
    },
  );

  useTraceById(
    { traceId: nextTrace?.id || "" },
    {
      enabled: !!nextTrace?.id,
      placeholderData: keepPreviousData,
      staleTime: STALE_TIME,
    },
  );

  const displayTrace = fullTrace || trace;

  const handleOpenTrace = useCallback(
    (shouldFilterToolCalls: boolean) => {
      setTracePanelFilters((previousFilters: Filter[] | null | undefined) =>
        manageToolFilter(previousFilters, shouldFilterToolCalls),
      );
      setTraceId(displayTrace?.id || "");
      setSpanId("");
    },
    [setTracePanelFilters, setTraceId, setSpanId, displayTrace?.id],
  );

  const handleClose = useCallback(() => {
    setTraceId("");
    setSpanId("");
  }, [setTraceId, setSpanId]);

  return {
    trace,
    displayTrace,
    isLoading,
    viewTrace: fullTrace && fullTrace.id === trace?.id ? fullTrace : trace,
    isFullTraceLoaded: Boolean(trace?.id) && fullTrace?.id === trace?.id,
    traceId,
    spanId,
    setSpanId,
    handleOpenTrace,
    handleClose,
  };
};

const TraceHeader: React.FC = () => {
  const { displayTrace, isLoading, handleOpenTrace } = useTraceData();

  return (
    <div className="flex h-10 shrink-0 items-center justify-between gap-2 border-b border-border bg-soft-background px-3">
      <div className="flex min-w-0 items-center gap-1.5">
        {isLoading && (
          <Loader2 className="size-3.5 shrink-0 animate-spin text-muted-slate" />
        )}
        <span className="comet-body-xs-accented shrink-0">Trace:</span>
        <BaseTraceDataTypeIcon type={TRACE_TYPE_FOR_TREE} />
        <TraceIdentifier
          name={displayTrace?.name}
          id={displayTrace?.id || ""}
        />
      </div>
      <div className="flex shrink-0 items-center gap-1">
        <Button
          variant="ghost"
          size="2xs"
          onClick={() => handleOpenTrace(false)}
        >
          Trace
          <ArrowUpRight className="ml-1 size-3" />
        </Button>
        {displayTrace?.has_tool_spans && (
          <Button
            variant="ghost"
            size="2xs"
            onClick={() => handleOpenTrace(true)}
          >
            Tool calls
            <ArrowUpRight className="ml-1 size-3" />
          </Button>
        )}
      </div>
    </div>
  );
};

const TraceContent: React.FC = () => {
  const {
    trace,
    displayTrace,
    viewTrace,
    isFullTraceLoaded,
    traceId,
    spanId,
    setSpanId,
    handleClose,
  } = useTraceData();

  const { annotationQueue } = useSMEFlow();
  const customHtml = annotationQueue?.annotator_view?.html;
  const kitVersion =
    annotationQueue?.annotator_view?.kit_version ?? LATEST_KIT_VERSION;
  const [viewFailure, setViewFailure] = useState<{
    traceId: string;
    message: string;
  }>();
  const viewError =
    viewFailure && viewFailure.traceId === viewTrace?.id
      ? viewFailure.message
      : undefined;

  return (
    <>
      {customHtml && viewTrace && !viewError ? (
        <SandboxedAnnotatorTraceView
          html={customHtml}
          kitVersion={kitVersion}
          trace={viewTrace}
          onError={(message, kind) => {
            if (!isFullTraceLoaded) return;
            setViewFailure({ traceId: viewTrace.id, message });
            trackEvent(OpikEvent.ANNOTATION_QUEUE_VIEW_FAILED, {
              queue_id: annotationQueue?.id,
              trace_id: viewTrace.id,
              kind,
            });
          }}
        />
      ) : (
        <div className="flex flex-col gap-3">
          {viewError && <AnnotatorViewFallbackAlert />}
          {displayTrace && <DefaultTraceView trace={displayTrace} />}
        </div>
      )}
      <TraceDetailsPanel
        projectId={trace?.project_id || ""}
        traceId={traceId ?? ""}
        spanId={spanId ?? ""}
        setSpanId={setSpanId}
        open={Boolean(traceId)}
        onClose={handleClose}
        hideAnnotateActions
      />
    </>
  );
};

const TraceDataViewer = {
  Header: TraceHeader,
  Content: TraceContent,
};

export default TraceDataViewer;
