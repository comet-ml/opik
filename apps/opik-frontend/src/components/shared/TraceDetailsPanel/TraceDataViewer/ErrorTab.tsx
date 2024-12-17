import React from "react";
import { Span, Trace } from "@/types/traces";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";

type ErrorTabProps = {
  data: Trace | Span;
};

const ErrorTab: React.FunctionComponent<ErrorTabProps> = ({ data }) => {
  const error = data.error_info!;

  return (
    <Accordion
      type="multiple"
      className="w-full"
      defaultValue={["type", "message", "traceback"]}
    >
      <AccordionItem value="type">
        <AccordionTrigger>Exception type</AccordionTrigger>
        <AccordionContent>
          <div className="whitespace-pre-wrap break-words rounded-md bg-primary-foreground px-4 py-2">
            {error.exception_type}
          </div>
        </AccordionContent>
      </AccordionItem>
      {error.message && (
        <AccordionItem value="message">
          <AccordionTrigger>Message</AccordionTrigger>
          <AccordionContent>
            <div className="whitespace-pre-wrap break-words rounded-md bg-primary-foreground px-4 py-2">
              {error.message}
            </div>
          </AccordionContent>
        </AccordionItem>
      )}
      <AccordionItem value="traceback">
        <AccordionTrigger>Traceback</AccordionTrigger>
        <AccordionContent>
          <div className="whitespace-pre-wrap break-words rounded-md bg-primary-foreground px-4 py-2">
            {error.traceback}
          </div>
        </AccordionContent>
      </AccordionItem>
    </Accordion>
  );
};

export default ErrorTab;
