import React, { useMemo } from "react";
import { Span, Trace } from "@/types/traces";
import { processInputData } from "@/lib/images";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import SyntaxHighlighter from "@/components/shared/SyntaxHighlighter/SyntaxHighlighter";
import AttachmentsList from "./AttachmentsList";

type InputOutputTabProps = {
  data: Trace | Span;
};

const InputOutputTab: React.FunctionComponent<InputOutputTabProps> = ({
  data,
}) => {
  const { images, formattedData } = useMemo(
    () => processInputData(data.input),
    [data.input],
  );

  return (
    <Accordion
      type="multiple"
      className="w-full"
      defaultValue={["attachments", "input", "output"]}
    >
      <AttachmentsList data={data} images={images} enabled={false} />
      <AccordionItem className="group" value="input">
        <AccordionTrigger>Input</AccordionTrigger>
        <AccordionContent
          forceMount
          className="group-data-[state=closed]:hidden"
        >
          <SyntaxHighlighter
            data={formattedData as object}
            prettifyConfig={{ fieldType: "input" }}
            preserveKey="syntax-highlighter-trace-sidebar-input"
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
            data={data.output}
            prettifyConfig={{ fieldType: "output" }}
            preserveKey="syntax-highlighter-trace-sidebar-output"
          />
        </AccordionContent>
      </AccordionItem>
    </Accordion>
  );
};

export default InputOutputTab;
