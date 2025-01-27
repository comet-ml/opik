import React, { useMemo } from "react";
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
  const hasTokenUsage = Boolean(data.usage);

  const openSections = useMemo(() => {
    return hasTokenUsage ? ["metadata", "usage"] : ["metadata"];
  }, [hasTokenUsage]);

  return (
    <Accordion type="multiple" className="w-full" defaultValue={openSections}>
      <AccordionItem value="metadata">
        <AccordionTrigger>Metadata</AccordionTrigger>
        <AccordionContent>
          <SyntaxHighlighter data={data.metadata} />
        </AccordionContent>
      </AccordionItem>
      {hasTokenUsage && (
        <AccordionItem value="usage">
          <AccordionTrigger>Token usage</AccordionTrigger>
          <AccordionContent>
            <SyntaxHighlighter data={data.usage as object} />
          </AccordionContent>
        </AccordionItem>
      )}
    </Accordion>
  );
};

export default MetadataTab;
