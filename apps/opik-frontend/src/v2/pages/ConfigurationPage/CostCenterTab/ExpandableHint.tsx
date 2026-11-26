import React from "react";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/ui/accordion";

type ExpandableHintProps = {
  label: string;
  icon?: React.ReactNode;
  contentTestId?: string;
  children: React.ReactNode;
};

const ExpandableHint: React.FC<ExpandableHintProps> = ({
  label,
  icon,
  contentTestId,
  children,
}) => (
  <Accordion type="single" collapsible>
    <AccordionItem value="hint" className="border-b-0">
      <AccordionTrigger className="comet-body-xs h-auto justify-start gap-1 px-0 py-0 text-muted-slate hover:text-foreground [&>svg]:size-3.5">
        {/* The icon sits in a span: the trigger rotates every svg directly inside it. */}
        <span className="flex items-center gap-1">
          {icon}
          {label}
        </span>
      </AccordionTrigger>
      <AccordionContent className="pb-0 pt-2">
        <div
          className="comet-body-xs space-y-4 rounded-md bg-muted px-5 py-4 text-muted-slate"
          data-testid={contentTestId}
        >
          {children}
        </div>
      </AccordionContent>
    </AccordionItem>
  </Accordion>
);

export default ExpandableHint;
