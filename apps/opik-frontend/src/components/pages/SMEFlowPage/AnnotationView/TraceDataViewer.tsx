import React from "react";
import { keepPreviousData } from "@tanstack/react-query";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import SyntaxHighlighter from "@/components/shared/SyntaxHighlighter/SyntaxHighlighter";
import { Trace } from "@/types/traces";
import { useSMEFlow } from "../SMEFlowContext";
import useTraceById from "@/api/traces/useTraceById";

const STALE_TIME = 5 * 60 * 1000; // 5 minutes

const TraceDataViewer: React.FC = () => {
  const { currentItem, nextItem } = useSMEFlow();

  const trace = currentItem as Trace;
  const nextTrace = nextItem as Trace | undefined;

  // Fetch full trace data (not truncated)
  const { data: fullTrace } = useTraceById(
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

  return (
    <div className="pr-4">
      <Accordion
        type="multiple"
        className="w-full"
        defaultValue={["input", "output"]}
      >
        <AccordionItem className="group" value="input" disabled>
          <AccordionTrigger className="pointer-events-none [&>svg]:hidden">
            Input
          </AccordionTrigger>
          <AccordionContent
            forceMount
            className="group-data-[state=closed]:hidden"
          >
            <SyntaxHighlighter
              data={displayTrace?.input || {}}
              prettifyConfig={{ fieldType: "input" }}
              preserveKey="syntax-highlighter-annotation-input"
              withSearch
            />
          </AccordionContent>
        </AccordionItem>

        <AccordionItem className="group" value="output" disabled>
          <AccordionTrigger className="pointer-events-none [&>svg]:hidden">
            Output
          </AccordionTrigger>
          <AccordionContent
            forceMount
            className="group-data-[state=closed]:hidden"
          >
            <SyntaxHighlighter
              data={displayTrace?.output || {}}
              prettifyConfig={{ fieldType: "output" }}
              preserveKey="syntax-highlighter-annotation-output"
              withSearch
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
            />
          </AccordionContent>
        </AccordionItem>
      </Accordion>
    </div>
  );
};

export default TraceDataViewer;
