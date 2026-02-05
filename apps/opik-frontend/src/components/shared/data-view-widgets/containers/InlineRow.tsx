import React from "react";
import { z } from "zod";
import { cn } from "@/lib/utils";

// ============================================================================
// TYPES
// ============================================================================

export type InlineRowBackground = "none" | "muted" | null;

export interface InlineRowWidgetProps {
  background?: InlineRowBackground;
  children?: React.ReactNode;
}

// ============================================================================
// CONFIG (for registry building)
// ============================================================================

export const inlineRowWidgetConfig = {
  type: "InlineRow" as const,
  category: "container" as const,
  schema: z.object({
    background: z
      .enum(["none", "muted"])
      .nullable()
      .optional()
      .describe(
        "Background style: none (transparent), muted (gray bg with padding/rounded)",
      ),
  }),
  description:
    "Horizontal flex container for inline widgets (tags, links, stats, etc.)",
};

// ============================================================================
// COMPONENT
// ============================================================================

/**
 * InlineRow - Horizontal flex container for inline widgets
 *
 * Figma reference: Node 240-17205, 364:19891
 * Styles:
 * - Display: flex row
 * - Gap: 8px (gap-2)
 * - Align: center
 * - Wrap: wrap (for overflow)
 * - Background muted: bg-slate-50 px-4 py-3 rounded-md (matches Figma 364:19891)
 */
export const InlineRowWidget: React.FC<InlineRowWidgetProps> = ({
  background = "none",
  children,
}) => {
  return (
    <div
      className={cn(
        "flex flex-row flex-wrap items-center gap-2",
        background === "muted" && "rounded-md bg-slate-50 px-4 py-3",
      )}
    >
      {children}
    </div>
  );
};

export default InlineRowWidget;
