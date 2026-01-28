import React from "react";
import { z } from "zod";
import { Check, X } from "lucide-react";
import { cn } from "@/lib/utils";
import { DynamicBoolean } from "@/lib/data-view";

// ============================================================================
// TYPES
// ============================================================================

export type BoolStyle = "check" | "text";

export interface BoolWidgetProps {
  value: boolean | null;
  style?: BoolStyle;
}

// ============================================================================
// CONFIG (for registry building)
// ============================================================================

export const boolWidgetConfig = {
  type: "Bool" as const,
  category: "inline" as const,
  schema: z.object({
    value: DynamicBoolean.describe("Boolean value"),
    style: z
      .enum(["check", "text"])
      .nullable()
      .optional()
      .describe('Display style: "check" for icon, "text" for true/false'),
  }),
  description:
    "Compact boolean value renderer. Use for key-value metadata (cached, streamed, is_error, success).",
};

// ============================================================================
// COMPONENT
// ============================================================================

/**
 * BoolWidget - Compact boolean value renderer
 *
 * Figma reference: Node 240-17129
 * Styles:
 * - check: Checkmark (green #00D41F) or X (red #F14668) icon, 16x16
 * - text: "true"/"false" text
 *
 * Use for key-value metadata: cached, streamed, is_error, success
 */
export const BoolWidget: React.FC<BoolWidgetProps> = ({
  value,
  style = "check",
}) => {
  if (value === null || value === undefined) return null;

  if (style === "text") {
    return (
      <span
        className={cn(
          "comet-body-s",
          value ? "text-success" : "text-destructive",
        )}
      >
        {value ? "true" : "false"}
      </span>
    );
  }

  // Check style (default)
  return value ? (
    <Check className="size-4 text-success" />
  ) : (
    <X className="size-4 text-destructive" />
  );
};

export default BoolWidget;
