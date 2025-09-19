import React, { useMemo } from "react";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import SyntaxHighlighter from "@/components/shared/SyntaxHighlighter/SyntaxHighlighter";
import AttachmentsList from "@/components/pages-shared/traces/TraceDetailsPanel/TraceDataViewer/AttachmentsList";
import Loader from "@/components/shared/Loader/Loader";
import { processInputData } from "@/lib/images";
import { Trace, Thread } from "@/types/traces";

interface ItemViewerProps {
  item: Trace | Thread | null;
  isLoading?: boolean;
  className?: string;
}

const ItemViewer: React.FunctionComponent<ItemViewerProps> = ({
  item,
  isLoading = false,
  className = "",
}) => {
  // Type guard for Trace vs Thread
  const isTrace = (item: Trace | Thread | null): item is Trace => {
    return item != null && "input" in item;
  };

  const currentTrace = isTrace(item) ? item : null;

  // Process input data with images (similar to InputOutputTab)
  const { images, formattedData } = useMemo(() => {
    if (!currentTrace?.input) {
      return { images: [], formattedData: null };
    }
    return processInputData(currentTrace.input);
  }, [currentTrace?.input]);

  const hasError = Boolean(currentTrace?.error_info);

  if (!item) {
    return (
      <div className={`flex h-64 items-center justify-center ${className}`}>
        <div className="text-center text-gray-500">No items to annotate</div>
      </div>
    );
  }

  return (
    <div className={`space-y-4 ${className}`}>
      {/* Data Viewer with Accordion */}
      <Accordion
        type="multiple"
        className="w-full"
        defaultValue={["attachments", "error", "input", "output", "metadata"]}
      >
        {currentTrace && (
          <AttachmentsList data={currentTrace} images={images} />
        )}

        {hasError && currentTrace?.error_info && (
          <AccordionItem className="group" value="error" disabled={isLoading}>
            <AccordionTrigger>Error</AccordionTrigger>
            <AccordionContent
              forceMount
              className="group-data-[state=closed]:hidden"
            >
              {isLoading ? (
                <Loader />
              ) : (
                <SyntaxHighlighter
                  data={currentTrace.error_info}
                  preserveKey="syntax-highlighter-annotation-error"
                  withSearch
                />
              )}
            </AccordionContent>
          </AccordionItem>
        )}

        <AccordionItem className="group" value="input" disabled={isLoading}>
          <AccordionTrigger>Input</AccordionTrigger>
          <AccordionContent
            forceMount
            className="group-data-[state=closed]:hidden"
          >
            {isLoading ? (
              <Loader />
            ) : (
              <SyntaxHighlighter
                data={formattedData || currentTrace?.input || {}}
                prettifyConfig={{ fieldType: "input" }}
                preserveKey="syntax-highlighter-annotation-input"
                withSearch
              />
            )}
          </AccordionContent>
        </AccordionItem>

        <AccordionItem className="group" value="output" disabled={isLoading}>
          <AccordionTrigger>Output</AccordionTrigger>
          <AccordionContent
            forceMount
            className="group-data-[state=closed]:hidden"
          >
            {isLoading ? (
              <Loader />
            ) : (
              <SyntaxHighlighter
                data={currentTrace?.output || {}}
                prettifyConfig={{ fieldType: "output" }}
                preserveKey="syntax-highlighter-annotation-output"
                withSearch
              />
            )}
          </AccordionContent>
        </AccordionItem>

        <AccordionItem className="group" value="metadata" disabled={isLoading}>
          <AccordionTrigger>Metadata</AccordionTrigger>
          <AccordionContent
            forceMount
            className="group-data-[state=closed]:hidden"
          >
            {isLoading ? (
              <Loader />
            ) : (
              <SyntaxHighlighter
                data={currentTrace?.metadata || {}}
                preserveKey="syntax-highlighter-annotation-metadata"
                withSearch
              />
            )}
          </AccordionContent>
        </AccordionItem>
      </Accordion>
    </div>
  );
};

export default ItemViewer;
