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
import { Spinner } from "@/components/ui/spinner";

type InputOutputTabProps = {
  data: Trace | Span;
  isLoading: boolean;
};

const InputOutputTab: React.FunctionComponent<InputOutputTabProps> = ({
  data,
  isLoading,
}) => {
  const { images, formattedData } = useMemo(
    () => processInputData(data.input),
    [data.input],
  );
  const hasError = Boolean(data.error_info);

  return (
    <Accordion
      type="multiple"
      className="w-full"
      defaultValue={["attachments", "error", "input", "output"]}
    >
      <AttachmentsList data={data} images={images} />
      {hasError && (
        <AccordionItem className="group" value="error" disabled={isLoading}>
          <AccordionTrigger>Error</AccordionTrigger>
          <AccordionContent
            forceMount
            className="group-data-[state=closed]:hidden"
          >
            {isLoading ? (
              <Spinner size="small" />
            ) : (
              <SyntaxHighlighter
                data={data.error_info!}
                prettifyConfig={{ fieldType: "input" }}
                preserveKey="syntax-highlighter-trace-sidebar-error"
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
            <Spinner size="small" />
          ) : (
            <SyntaxHighlighter
              data={formattedData as object}
              prettifyConfig={{ fieldType: "input" }}
              preserveKey="syntax-highlighter-trace-sidebar-input"
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
            <Spinner size="small" />
          ) : (
            <SyntaxHighlighter
              data={data.output}
              prettifyConfig={{ fieldType: "output" }}
              preserveKey="syntax-highlighter-trace-sidebar-output"
            />
          )}
        </AccordionContent>
      </AccordionItem>
    </Accordion>
  );
};

export default InputOutputTab;
