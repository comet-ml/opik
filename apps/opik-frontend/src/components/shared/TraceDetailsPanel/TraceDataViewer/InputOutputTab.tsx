import React from "react";
import { Span, Trace } from "@/types/traces";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import SyntaxHighlighter from "@/components/shared/SyntaxHighlighter/SyntaxHighlighter";

type InputOutputTabProps = {
  data: Trace | Span;
};

const InputOutputTab: React.FunctionComponent<InputOutputTabProps> = ({
  data,
}) => {
  return (
    <Accordion
      type="multiple"
      className="w-full"
      defaultValue={["input", "output"]}
    >
      <AccordionItem value="input">
        <AccordionTrigger>Input</AccordionTrigger>
        <AccordionContent>
          <SyntaxHighlighter data={data.input} />
        </AccordionContent>
      </AccordionItem>
      <AccordionItem value="output">
        <AccordionTrigger>Output</AccordionTrigger>
        <AccordionContent>
          <SyntaxHighlighter data={data.output} />
        </AccordionContent>
      </AccordionItem>
    </Accordion>
  );
};

export default InputOutputTab;
