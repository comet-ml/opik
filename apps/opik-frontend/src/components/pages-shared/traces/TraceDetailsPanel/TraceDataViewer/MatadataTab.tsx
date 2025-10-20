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
  search?: string;
};

const MetadataTab: React.FunctionComponent<MetadataTabProps> = ({
  data,
  search,
}) => {
  const hasTokenUsage = Boolean(data.usage);

  const usageData = useMemo(() => {
    if (!data.usage) return null;

    // For Spans, include provider information if available
    if ("provider" in data && data.provider) {
      return {
        provider: data.provider,
        ...data.usage,
      };
    }

    // For Traces, include providers array if available
    if ("providers" in data && data.providers && data.providers.length > 0) {
      return {
        providers: data.providers,
        ...data.usage,
      };
    }

    return data.usage;
  }, [data]);

  const openSections = useMemo(() => {
    return hasTokenUsage ? ["metadata", "usage"] : ["metadata"];
  }, [hasTokenUsage]);

  return (
    <Accordion type="multiple" className="w-full" defaultValue={openSections}>
      <AccordionItem value="metadata">
        <AccordionTrigger>Metadata</AccordionTrigger>
        <AccordionContent>
          <SyntaxHighlighter withSearch data={data.metadata} search={search} />
        </AccordionContent>
      </AccordionItem>
      {hasTokenUsage && (
        <AccordionItem value="usage">
          <AccordionTrigger>Token usage</AccordionTrigger>
          <AccordionContent>
            <SyntaxHighlighter
              data={usageData as object}
              withSearch
              search={search}
            />
          </AccordionContent>
        </AccordionItem>
      )}
    </Accordion>
  );
};

export default MetadataTab;
