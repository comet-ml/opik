import React from "react";
import { Span, Trace } from "@/types/traces";
import { useUnifiedMedia } from "@/hooks/useUnifiedMedia";
import { MediaProvider } from "@/components/shared/SyntaxHighlighter/llmMessages";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import SyntaxHighlighter from "@/components/shared/SyntaxHighlighter/SyntaxHighlighter";
import AttachmentsList from "./AttachmentsList";
import EventsList from "./EventsList";
import Loader from "@/components/shared/Loader/Loader";

type InputOutputTabProps = {
  data: Trace | Span;
  isLoading: boolean;
  search?: string;
};

const InputOutputTab: React.FunctionComponent<InputOutputTabProps> = ({
  data,
  isLoading,
  search,
}) => {
  // Use unified media hook to fetch all media and get transformed data
  const { media, transformedInput, transformedOutput } = useUnifiedMedia(data);

  const hasError = Boolean(data.error_info);

  return (
    <MediaProvider media={media}>
      <Accordion
        type="multiple"
        className="w-full"
        defaultValue={["attachments", "error", "input", "output", "events"]}
      >
        <AttachmentsList media={media} />
        {hasError && (
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
                  data={data.error_info!}
                  preserveKey="syntax-highlighter-trace-sidebar-error"
                  withSearch
                  search={search}
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
                data={transformedInput}
                prettifyConfig={{ fieldType: "input" }}
                preserveKey="syntax-highlighter-trace-sidebar-input"
                search={search}
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
                data={transformedOutput}
                prettifyConfig={{ fieldType: "output" }}
                preserveKey="syntax-highlighter-trace-sidebar-output"
                search={search}
                withSearch
              />
            )}
          </AccordionContent>
        </AccordionItem>
        <EventsList data={data} isLoading={isLoading} search={search} />
      </Accordion>
    </MediaProvider>
  );
};

export default InputOutputTab;
