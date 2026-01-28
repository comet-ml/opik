import React from "react";
import { z } from "zod";
import { ChevronDown, Braces, Check, Database, Sparkles } from "lucide-react";
import {
  Accordion,
  AccordionItem,
  AccordionContent,
  CustomAccordionTrigger,
} from "@/components/ui/accordion";
import { cn } from "@/lib/utils";
import {
  DynamicString,
  DynamicBoolean,
  NullableDynamicString,
} from "@/lib/data-view";

// ============================================================================
// TYPES
// ============================================================================

export type Level2IconType = "tool" | "retrieval" | "generation" | null;
export type Level2StatusType = "success" | "error" | "pending" | null;

export interface Level2ContainerWidgetProps {
  summary: string;
  defaultOpen?: boolean;
  icon: Level2IconType;
  status?: Level2StatusType;
  duration?: string | null;
  children?: React.ReactNode;
}

// ============================================================================
// CONFIG (for registry building)
// ============================================================================

export const level2ContainerWidgetConfig = {
  type: "Level2Container" as const,
  category: "container" as const,
  schema: z.object({
    summary: DynamicString.describe(
      "Summary text displayed in collapsed state",
    ),
    defaultOpen: DynamicBoolean.optional().describe(
      "Whether the container is open by default",
    ),
    icon: z
      .enum(["tool", "retrieval", "generation"])
      .nullable()
      .describe(
        "Icon type for visual identification (tool/retrieval/generation). Required - LLM must choose an appropriate icon or explicitly set to null.",
      ),
    status: z
      .enum(["success", "error", "pending"])
      .nullable()
      .optional()
      .describe("Status indicator (success shows checkmark, error shows X)"),
    duration: NullableDynamicString.describe(
      "Duration text displayed on the right (e.g., '42ms')",
    ),
  }),
  description:
    "Collapsible detail disclosure for spans, tools, system internals.",
};

// ============================================================================
// ICON STYLES
// ============================================================================

const iconBackgrounds: Record<NonNullable<Level2IconType>, string> = {
  tool: "bg-[#dafbf0]", // Teal background from Figma
  retrieval: "bg-[#e2effd]", // Blue background
  generation: "bg-[#fef3c7]", // Amber background
};

const iconColors: Record<NonNullable<Level2IconType>, string> = {
  tool: "text-[#295747]", // Dark teal
  retrieval: "text-[#1e40af]", // Dark blue
  generation: "text-[#92400e]", // Dark amber
};

const IconComponents: Record<
  NonNullable<Level2IconType>,
  React.FC<{ className?: string }>
> = {
  tool: Braces,
  retrieval: Database,
  generation: Sparkles,
};

// ============================================================================
// COMPONENT
// ============================================================================

/**
 * Level2Container - Collapsible detail disclosure
 *
 * Figma reference: Node 239-15701, 245-15436
 * Styles:
 * - Header: flex row, gap-1, items-center, py-1, px-0.5
 * - Chevron: 14px, rotates on open
 * - Icon: 20px container, 12px icon (teal bg #DAFBF0 for tool type)
 * - Summary text: comet-body-s-accented (14px Medium), text-muted-slate (#94A3B8)
 * - Status: checkmark for success (green), X for error (red)
 * - Duration: right-aligned, muted text
 * - Content: padding for children
 * - Border radius: 6px
 */
export const Level2ContainerWidget: React.FC<Level2ContainerWidgetProps> = ({
  summary,
  defaultOpen = true,
  icon,
  status,
  duration,
  children,
}) => {
  const itemValue = "level2-item";

  const IconComponent = icon ? IconComponents[icon] : null;

  return (
    <Accordion
      type="single"
      collapsible
      defaultValue={defaultOpen ? itemValue : undefined}
      className="w-full"
    >
      <AccordionItem
        value={itemValue}
        className="border-none [&[data-state=open]]:border-t [&[data-state=open]]:border-border"
      >
        <CustomAccordionTrigger
          className={cn(
            "flex w-full items-center gap-1.5 rounded-md px-0.5 py-1 text-left",
            "transition-colors hover:bg-muted/50",
            "[&[data-state=open]>svg:first-child]:rotate-180",
          )}
        >
          <ChevronDown className="size-3.5 shrink-0 text-muted-slate transition-transform duration-200" />

          {/* Icon indicator */}
          {icon && IconComponent && (
            <div
              className={cn(
                "flex size-5 shrink-0 items-center justify-center rounded",
                iconBackgrounds[icon],
              )}
            >
              <IconComponent className={cn("size-3", iconColors[icon])} />
            </div>
          )}

          {/* Summary text */}
          <span className="comet-body-s-accented flex-1 text-muted-slate">
            {summary}
          </span>

          {/* Status indicator */}
          {status === "success" && (
            <Check className="size-3.5 shrink-0 text-green-500" />
          )}
          {status === "error" && (
            <span className="size-3.5 shrink-0 text-red-500">✕</span>
          )}

          {/* Duration */}
          {duration && (
            <span className="comet-body-xs shrink-0 text-light-slate">
              {duration}
            </span>
          )}
        </CustomAccordionTrigger>
        <AccordionContent className="pb-3 pl-2">
          <div className="flex flex-col gap-1 border-l border-border pl-3">
            {children}
          </div>
        </AccordionContent>
      </AccordionItem>
    </Accordion>
  );
};

export default Level2ContainerWidget;
