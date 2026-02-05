import React from "react";
import { z } from "zod";
import { cn } from "@/lib/utils";

// ============================================================================
// TYPES
// ============================================================================

export type ContainerLayout = "stack" | "row" | null;
export type ContainerGap = "none" | "sm" | "md" | "lg" | null;
export type ContainerPadding = "none" | "sm" | "md" | "lg" | null;

export interface ContainerWidgetProps {
  layout?: ContainerLayout;
  gap?: ContainerGap;
  padding?: ContainerPadding;
  children?: React.ReactNode;
}

// ============================================================================
// CONFIG (for registry building)
// ============================================================================

export const containerWidgetConfig = {
  type: "Container" as const,
  category: "container" as const,
  schema: z.object({
    layout: z
      .enum(["stack", "row"])
      .nullable()
      .optional()
      .describe("Layout mode: stack (vertical), row (horizontal wrap)"),
    gap: z
      .enum(["none", "sm", "md", "lg"])
      .nullable()
      .optional()
      .describe("Gap between children: none, sm (8px), md (16px), lg (24px)"),
    padding: z
      .enum(["none", "sm", "md", "lg"])
      .nullable()
      .optional()
      .describe("Inner padding: none, sm (8px), md (16px), lg (24px)"),
  }),
  description:
    "Generic layout container with flexible layout presets. Use as root widget for single-column layouts.",
};

// ============================================================================
// STYLE MAPPINGS
// ============================================================================

const layoutClasses: Record<NonNullable<ContainerLayout>, string> = {
  stack: "flex flex-col",
  row: "flex flex-row flex-wrap items-start",
};

const gapClasses: Record<NonNullable<ContainerGap>, string> = {
  none: "",
  sm: "gap-2",
  md: "gap-4",
  lg: "gap-6",
};

const paddingClasses: Record<NonNullable<ContainerPadding>, string> = {
  none: "",
  sm: "p-2",
  md: "p-4",
  lg: "p-6",
};

// ============================================================================
// COMPONENT
// ============================================================================

/**
 * Container - Generic layout container
 *
 * Key differences from Level1Container:
 * - Transparent background (no border/shadow)
 * - No title
 * - Layout presets (stack, row)
 * - Generic wrapper vs semantic section
 *
 * Use cases:
 * - Root widget for single-column layouts
 * - Flexible row/column arrangements
 */
export const ContainerWidget: React.FC<ContainerWidgetProps> = ({
  layout = "stack",
  gap = "md",
  padding = "none",
  children,
}) => {
  const layoutClass = layoutClasses[layout ?? "stack"];
  const gapClass = gapClasses[gap ?? "md"];
  const paddingClass = paddingClasses[padding ?? "none"];

  return (
    <div className={cn(layoutClass, gapClass, paddingClass)}>{children}</div>
  );
};

export default ContainerWidget;
