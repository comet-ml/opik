import React, { useMemo } from "react";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import SyntaxHighlighter from "@/components/shared/SyntaxHighlighter/SyntaxHighlighter";
import { Trace } from "@/types/traces";
import { useSMEFlow } from "../SMEFlowContext";

const TraceDataViewer: React.FunctionComponent = () => {
  const { currentItem } = useSMEFlow();

  const trace = currentItem as Trace;

  return (
    <div className="pr-4">
      <Accordion
        type="multiple"
        className="w-full"
        defaultValue={["input", "output"]}
      >
        <AccordionItem className="group" value="input" disabled>
          <AccordionTrigger className="[&>svg]:hidden">Input</AccordionTrigger>
          <AccordionContent
            forceMount
            className="group-data-[state=closed]:hidden"
          >
            <SyntaxHighlighter
              data={trace?.input || {}}
              prettifyConfig={{ fieldType: "input" }}
              preserveKey="syntax-highlighter-annotation-input"
              withSearch
            />
          </AccordionContent>
        </AccordionItem>

        <AccordionItem className="group" value="output" disabled>
          <AccordionTrigger className="[&>svg]:hidden">Output</AccordionTrigger>
          <AccordionContent
            forceMount
            className="group-data-[state=closed]:hidden"
          >
            <SyntaxHighlighter
              data={trace?.output || {}}
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
              data={trace?.metadata || {}}
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
