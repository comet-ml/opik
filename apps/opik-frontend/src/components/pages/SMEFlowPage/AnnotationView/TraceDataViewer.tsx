import React, { useCallback } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { Loader2 } from "lucide-react";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import SyntaxHighlighter from "@/components/shared/SyntaxHighlighter/SyntaxHighlighter";
import { Trace } from "@/types/traces";
import { useSMEFlow } from "../SMEFlowContext";
import { useAnnotationTreeState } from "./AnnotationTreeStateContext";
import useTraceById from "@/api/traces/useTraceById";
import { useUnifiedMedia } from "@/hooks/useUnifiedMedia";
import { MediaProvider } from "@/components/shared/PrettyLLMMessage/llmMessages";
import AttachmentsList from "@/components/pages-shared/traces/TraceDetailsPanel/TraceDataViewer/AttachmentsList";

const STALE_TIME = 5 * 60 * 1000; // 5 minutes

const TraceDataViewer: React.FC = () => {
  const { currentItem, nextItem } = useSMEFlow();
  const { state, updateScrollTop } = useAnnotationTreeState();

  const trace = currentItem as Trace;
  const nextTrace = nextItem as Trace | undefined;

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
    <div className="relative pr-4">
      {isFetching && (
        <div className="absolute right-6 top-2 z-10">
          <Loader2 className="size-4 animate-spin text-slate-400" />
        </div>
      )}
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
  );
};

export default TraceDataViewer;
