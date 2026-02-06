import React from "react";
import { z } from "zod";
import { ChevronDown } from "lucide-react";
import {
  Accordion,
  AccordionItem,
  AccordionContent,
  CustomAccordionTrigger,
} from "@/components/ui/accordion";
import { cn } from "@/lib/utils";
import { NullableDynamicString, DynamicBoolean } from "@/lib/data-view";

// ============================================================================
// TYPES
// ============================================================================

export interface Level1ContainerWidgetProps {
  title?: string | null;
  defaultOpen?: boolean;
  collapsible?: boolean;
  children?: React.ReactNode;
}

// ============================================================================
// CONFIG (for registry building)
// ============================================================================

export const level1ContainerWidgetConfig = {
  type: "Level1Container" as const,
  category: "container" as const,
  schema: z.object({
    title: NullableDynamicString.optional().describe(
      "Title text displayed in the accordion header",
    ),
    defaultOpen: DynamicBoolean.optional().describe(
      "Whether the container is open by default",
    ),
    collapsible: DynamicBoolean.optional().describe(
      "Whether the container can be collapsed. Set to false for thread/conversation views that should always be expanded.",
    ),
  }),
  description:
    "Collapsible section with border top/bottom and chevron arrow. Use for grouping related content. Set collapsible=false for thread views.",
};

// ============================================================================
// COMPONENT
// ============================================================================

/**
 * Level1Container - Collapsible section container
 *
 * Figma reference: Node 239-10307 (AccordionItem)
 * Styles:
 * - Border: top and bottom only, color #e2e8f0 (border-border)
 * - Header: title text + chevron arrow on right
 * - Content: no inner borders or padding
 * - No background color (transparent)
 *
 * Constraints:
 * - Cannot contain Input, Output, or Turn widgets (AI constraint)
 * - Set collapsible=false for thread/conversation views that should never collapse
 */
export const Level1ContainerWidget: React.FC<Level1ContainerWidgetProps> = ({
  title,
  defaultOpen = false,
  collapsible = true,
  children,
}) => {
  const itemValue = "level1-item";

  // Non-collapsible mode: render content directly with header (no accordion)
  if (!collapsible) {
    return (
      <div className="w-full border-y border-border">
        <div className="flex w-full items-center gap-1.5 px-3 py-1.5">
          <span className="comet-body-s flex-1 text-foreground">
            {title || "Section"}
          </span>
        </div>
        <div className="pb-3 pt-2">
          <div className="flex flex-col gap-4">{children}</div>
        </div>
      </div>
    );
  }

  // Collapsible mode: use accordion
  return (
    <Accordion
      type="single"
      collapsible
      defaultValue={defaultOpen ? itemValue : undefined}
      className="w-full"
    >
      <AccordionItem value={itemValue} className="border-y border-border">
        <CustomAccordionTrigger
          className={cn(
            "flex w-full items-center gap-1.5 px-3 py-1.5 text-left",
            "transition-colors hover:bg-muted/50",
            "[&[data-state=open]>svg]:rotate-180",
          )}
        >
          {/* Title text */}
          <span className="comet-body-s flex-1 text-foreground">
            {title || "Section"}
          </span>

          {/* Chevron arrow */}
          <ChevronDown className="size-3.5 shrink-0 text-muted-slate transition-transform duration-200" />
        </CustomAccordionTrigger>
        <AccordionContent className="pb-3 pt-2">
          <div className="flex flex-col gap-4">{children}</div>
        </AccordionContent>
      </AccordionItem>
    </Accordion>
  );
};

export default Level1ContainerWidget;
