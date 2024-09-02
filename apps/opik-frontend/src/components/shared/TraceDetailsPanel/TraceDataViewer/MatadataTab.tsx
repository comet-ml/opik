import React from "react";
import { Span, Trace } from "@/types/traces";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import SyntaxHighlighter from "@/components/shared/SyntaxHighlighter/SyntaxHighlighter";

type MetadataTabProps = {
  data: Trace | Span;
};

const MetadataTab: React.FunctionComponent<MetadataTabProps> = ({ data }) => {
  return (
    <Accordion type="multiple" className="w-full" defaultValue={["metadata"]}>
      <AccordionItem value="metadata">
        <AccordionTrigger>Metadata</AccordionTrigger>
        <AccordionContent>
          <SyntaxHighlighter data={data.metadata} />
        </AccordionContent>
      </AccordionItem>
    </Accordion>
  );
};

export default MetadataTab;
