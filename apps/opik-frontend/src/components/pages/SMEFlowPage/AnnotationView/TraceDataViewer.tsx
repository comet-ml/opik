import React, { useCallback } from "react";
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
  const { state, updateExpanded, updateScrollTop } = useAnnotationTreeState();

  const trace = currentItem as Trace;

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
              data={trace?.input || {}}
              prettifyConfig={{ fieldType: "input" }}
              preserveKey="syntax-highlighter-annotation-input"
              withSearch
              controlledExpanded={state.input.expanded}
              onExpandedChange={handleInputExpandedChange}
              scrollPosition={state.input.scrollTop}
              onScrollPositionChange={handleInputScrollChange}
              maxHeight="400px"
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
              data={trace?.output || {}}
              prettifyConfig={{ fieldType: "output" }}
              preserveKey="syntax-highlighter-annotation-output"
              withSearch
              controlledExpanded={state.output.expanded}
              onExpandedChange={handleOutputExpandedChange}
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
              data={trace?.metadata || {}}
              preserveKey="syntax-highlighter-annotation-metadata"
              withSearch
              maxHeight="400px"
            />
          </AccordionContent>
        </AccordionItem>
      </Accordion>
    </div>
  );
};

export default TraceDataViewer;
