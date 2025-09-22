import React, { useMemo } from "react";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import SyntaxHighlighter from "@/components/shared/SyntaxHighlighter/SyntaxHighlighter";
import { Trace, Thread } from "@/types/traces";
import { isObjectThread } from "@/lib/traces";

interface InputOutputViewerProps {
  item?: Trace | Thread;
}

const InputOutputViewer: React.FunctionComponent<InputOutputViewerProps> = ({
  item,
}) => {
  const { input, output, metadata } = useMemo(() => {
    if (!item) return {};

    if (isObjectThread(item)) {
      const thread = item as Thread;

      return {
        input: thread.first_message,
        output: thread.last_message,
      };
    } else {
      const trace = item as Trace;
      return {
        input: trace.input,
        output: trace.output,
        metadata: trace.metadata,
      };
    }
  }, [item]);

  return (
    <div>
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
              data={input || {}}
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
              data={output || {}}
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
              data={metadata || {}}
              preserveKey="syntax-highlighter-annotation-metadata"
              withSearch
            />
          </AccordionContent>
        </AccordionItem>
      </Accordion>
    </div>
  );
};

export default InputOutputViewer;
