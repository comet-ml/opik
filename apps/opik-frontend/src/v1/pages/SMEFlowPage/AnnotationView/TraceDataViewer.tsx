import React, { useCallback } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { JsonParam, StringParam, useQueryParam } from "use-query-params";
import { ListTree, Loader2 } from "lucide-react";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/ui/accordion";
import SyntaxHighlighter from "@/shared/SyntaxHighlighter/SyntaxHighlighter";
import { Trace } from "@/types/traces";
import { Filter } from "@/types/filters";
import { useSMEFlow } from "../SMEFlowContext";
import { useAnnotationTreeState } from "./AnnotationTreeStateContext";
import useTraceById from "@/api/traces/useTraceById";
import { useUnifiedMedia } from "@/hooks/useUnifiedMedia";
import { MediaProvider } from "@/shared/PrettyLLMMessage/llmMessages";
import AttachmentsList from "@/v1/pages-shared/traces/TraceDetailsPanel/TraceDataViewer/AttachmentsList";
import TraceDetailsPanel from "@/v1/pages-shared/traces/TraceDetailsPanel/TraceDetailsPanel";
import { manageToolFilter } from "@/v1/pages-shared/traces/spanTypeFilter";
import TraceIdentifier from "./TraceIdentifier";
import { Button } from "@/ui/button";

const STALE_TIME = 5 * 60 * 1000; // 5 minutes

const TraceDataViewer: React.FC = () => {
  const { currentItem, nextItem } = useSMEFlow();
  const { state, updateScrollTop } = useAnnotationTreeState();

  const trace = currentItem as Trace;
  const nextTrace = nextItem as Trace | undefined;

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

  // Fetch full trace data (not truncated)
  const { data: fullTrace, isFetching } = useTraceById(
    {
      traceId: trace?.id || "",
    },
    {
      enabled: !!trace?.id,
      placeholderData: keepPreviousData,
      staleTime: STALE_TIME,
    },
  );

  // Preload next trace data
  useTraceById(
    {
      traceId: nextTrace?.id || "",
    },
    {
      enabled: !!nextTrace?.id,
      placeholderData: keepPreviousData,
      staleTime: STALE_TIME,
    },
  );

  const displayTrace = fullTrace || trace;

  // Use unified media hook to fetch all media and get transformed data
  const { media, transformedInput, transformedOutput } =
    useUnifiedMedia(displayTrace);

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

  // Handlers for scroll position changes
  const handleInputScrollChange = useCallback(
    (updaterOrValue: number | ((old: number) => number)) => {
      const newScrollTop =
        typeof updaterOrValue === "function"
          ? updaterOrValue(state.input.scrollTop)
          : updaterOrValue;
      updateScrollTop("input", newScrollTop);
    },
    [updateScrollTop, state.input.scrollTop],
  );

  const handleOutputScrollChange = useCallback(
    (updaterOrValue: number | ((old: number) => number)) => {
      const newScrollTop =
        typeof updaterOrValue === "function"
          ? updaterOrValue(state.output.scrollTop)
          : updaterOrValue;
      updateScrollTop("output", newScrollTop);
    },
    [updateScrollTop, state.output.scrollTop],
  );

  return (
    <>
      <div className="relative pr-4">
        <TraceIdentifier
          label="Trace"
          name={displayTrace?.name}
          id={displayTrace?.id || ""}
        />
        {isFetching && (
          <div className="absolute right-6 top-2 z-10">
            <Loader2 className="size-4 animate-spin text-slate-400" />
          </div>
        )}
        <div className="mb-3 flex gap-2">
          <Button
            variant="ghost"
            size="2xs"
            onClick={() => handleOpenTrace(false)}
          >
            <ListTree className="mr-1 size-3" />
            View trace
          </Button>
          {displayTrace?.has_tool_spans && (
            <Button
              variant="ghost"
              size="2xs"
              onClick={() => handleOpenTrace(true)}
            >
              View tool calls
            </Button>
          )}
        </div>
        <MediaProvider media={media}>
          <Accordion
            type="multiple"
            className="w-full"
            defaultValue={["attachments", "input", "output"]}
          >
            {displayTrace && <AttachmentsList media={media} />}
            <AccordionItem className="group" value="input">
              <AccordionTrigger>Input</AccordionTrigger>
              <AccordionContent
                forceMount
                className="group-data-[state=closed]:hidden"
              >
                <SyntaxHighlighter
                  data={transformedInput}
                  prettifyConfig={{ fieldType: "input" }}
                  preserveKey="syntax-highlighter-annotation-input"
                  withSearch
                  scrollPosition={state.input.scrollTop}
                  onScrollPositionChange={handleInputScrollChange}
                  maxHeight="400px"
                />
              </AccordionContent>
            </AccordionItem>

            <AccordionItem className="group" value="output">
              <AccordionTrigger>Output</AccordionTrigger>
              <AccordionContent
                forceMount
                className="group-data-[state=closed]:hidden"
              >
                <SyntaxHighlighter
                  data={transformedOutput}
                  prettifyConfig={{ fieldType: "output" }}
                  preserveKey="syntax-highlighter-annotation-output"
                  withSearch
                  scrollPosition={state.output.scrollTop}
                  onScrollPositionChange={handleOutputScrollChange}
                  maxHeight="400px"
                />
              </AccordionContent>
            </AccordionItem>

            <AccordionItem className="group" value="metadata">
              <AccordionTrigger>Metadata</AccordionTrigger>
              <AccordionContent
                forceMount
                className="group-data-[state=closed]:hidden"
              >
                <SyntaxHighlighter
                  data={displayTrace?.metadata || {}}
                  preserveKey="syntax-highlighter-annotation-metadata"
                  withSearch
                  maxHeight="400px"
                />
              </AccordionContent>
            </AccordionItem>
          </Accordion>
        </MediaProvider>
      </div>
      <TraceDetailsPanel
        projectId={trace?.project_id || ""}
        traceId={traceId ?? ""}
        spanId={spanId ?? ""}
        setSpanId={setSpanId}
        open={Boolean(traceId)}
        onClose={handleClose}
      />
    </>
  );
};

export default TraceDataViewer;
