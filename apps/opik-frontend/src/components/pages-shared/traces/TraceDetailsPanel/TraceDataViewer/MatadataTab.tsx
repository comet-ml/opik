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
    const previousUsageData = data.usage ?? {};

    // For Spans, include provider information if available
    if ("provider" in data && data.provider) {
      return {
        ...previousUsageData,
        provider: data.provider,
      };
    }

    // For Traces, include providers array
    if ("providers" in data && data.providers) {
      return {
        ...previousUsageData,
        providers: data.providers,
      };
    }

    return previousUsageData;
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
