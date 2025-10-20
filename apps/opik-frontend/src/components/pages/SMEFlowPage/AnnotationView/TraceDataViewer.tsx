import React, { useRef, useEffect, useCallback } from "react";
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
import { ExpandedState } from "@tanstack/react-table";

const TraceDataViewer: React.FC = () => {
  const { currentItem } = useSMEFlow();
  const { state, updateExpanded, updateScrollTop, getScrollTop } =
    useAnnotationTreeState();

  const trace = currentItem as Trace;

  // Refs for scroll containers
  const inputScrollRef = useRef<HTMLDivElement>(null);
  const outputScrollRef = useRef<HTMLDivElement>(null);

  // Save scroll position when user scrolls
  const createScrollHandler = useCallback(
    (section: "input" | "output") => {
      return (e: React.UIEvent<HTMLDivElement>) => {
        const scrollTop = e.currentTarget.scrollTop;
        updateScrollTop(section, scrollTop);
      };
    },
    [updateScrollTop],
  );

  // Restore scroll positions when trace changes
  useEffect(() => {
    if (inputScrollRef.current) {
      inputScrollRef.current.scrollTop = getScrollTop("input");
    }
    if (outputScrollRef.current) {
      outputScrollRef.current.scrollTop = getScrollTop("output");
    }
  }, [trace, getScrollTop]);

  // Handlers for expanded state changes
  const handleInputExpandedChange = useCallback(
    (
      updaterOrValue: ExpandedState | ((old: ExpandedState) => ExpandedState),
    ) => {
      updateExpanded("input", updaterOrValue);
    },
    [updateExpanded],
  );

  const handleOutputExpandedChange = useCallback(
    (
      updaterOrValue: ExpandedState | ((old: ExpandedState) => ExpandedState),
    ) => {
      updateExpanded("output", updaterOrValue);
    },
    [updateExpanded],
  );

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
            <div
              ref={inputScrollRef}
              onScroll={createScrollHandler("input")}
              className="max-h-[400px] overflow-y-auto"
            >
              <SyntaxHighlighter
                data={trace?.input || {}}
                prettifyConfig={{ fieldType: "input" }}
                preserveKey="syntax-highlighter-annotation-input"
                withSearch
                controlledExpanded={state.input.expanded}
                onExpandedChange={handleInputExpandedChange}
              />
            </div>
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
            <div
              ref={outputScrollRef}
              onScroll={createScrollHandler("output")}
              className="max-h-[400px] overflow-y-auto"
            >
              <SyntaxHighlighter
                data={trace?.output || {}}
                prettifyConfig={{ fieldType: "output" }}
                preserveKey="syntax-highlighter-annotation-output"
                withSearch
                controlledExpanded={state.output.expanded}
                onExpandedChange={handleOutputExpandedChange}
              />
            </div>
          </AccordionContent>
        </AccordionItem>

        <AccordionItem className="group" value="metadata">
          <AccordionTrigger>Metadata</AccordionTrigger>
          <AccordionContent
            forceMount
            className="group-data-[state=closed]:hidden"
          >
            <div className="max-h-[400px] overflow-y-auto">
              <SyntaxHighlighter
                data={trace?.metadata || {}}
                preserveKey="syntax-highlighter-annotation-metadata"
                withSearch
              />
            </div>
          </AccordionContent>
        </AccordionItem>
      </Accordion>
    </div>
  );
};

export default TraceDataViewer;
