import React from "react";
import { ChevronDown } from "lucide-react";
import { cn } from "@/lib/utils";
import {
  Accordion,
  AccordionItem,
  AccordionContent,
  CustomAccordionTrigger,
} from "@/components/ui/accordion";
import TreeJsonViewer from "./TreeJsonViewer";
import TreeRenderer from "./TreeRenderer";
import type { ViewTree, SourceData } from "@/lib/data-view/core/types";

interface WidgetExampleSectionProps {
  title: string;
  tree: ViewTree;
  sourceData: SourceData;
}

const WidgetExampleSection: React.FC<WidgetExampleSectionProps> = ({
  title,
  tree,
  sourceData,
}) => {
  const itemValue = "example-section";

  return (
    <Accordion type="single" collapsible className="w-full">
      <AccordionItem value={itemValue} className="border-none">
        <CustomAccordionTrigger
          className={cn(
            "flex w-full items-center gap-2 rounded-md px-3 py-2",
            "bg-muted/50 hover:bg-muted transition-colors",
            "[&[data-state=open]>svg]:rotate-180",
          )}
        >
          <ChevronDown className="size-4 shrink-0 text-muted-foreground transition-transform duration-200" />
          <span className="text-sm font-medium">{title}</span>
        </CustomAccordionTrigger>
        <AccordionContent className="pb-0">
          <div className="flex flex-col gap-4 pt-4 lg:flex-row">
            <TreeJsonViewer data={tree} />
            <TreeRenderer tree={tree} sourceData={sourceData} />
          </div>
        </AccordionContent>
      </AccordionItem>
    </Accordion>
  );
};

export default WidgetExampleSection;
