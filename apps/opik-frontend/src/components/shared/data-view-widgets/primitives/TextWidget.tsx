import React from "react";
import { z } from "zod";
import { cn } from "@/lib/utils";
import { DynamicString, NullableDynamicBoolean } from "@/lib/data-view";

// ============================================================================
// TYPES
// ============================================================================

export interface TextWidgetProps {
  value: string;
  variant?: "body" | "body-small" | "caption";
  truncate?: boolean;
  monospace?: boolean;
}

// ============================================================================
// CONFIG (for registry building)
// ============================================================================

export const textWidgetConfig = {
  type: "Text" as const,
  category: "inline" as const,
  schema: z.object({
    value: DynamicString,
    variant: z
      .enum(["body", "body-small", "caption"])
      .nullable()
      .optional()
      .describe(
        "Text style variant: body-small (default, 14px), body (16px), or caption (smaller, muted)",
      ),
    truncate: NullableDynamicBoolean.describe("Truncate with ellipsis"),
    monospace: NullableDynamicBoolean.describe("Use monospace font"),
  }),
  description: "Inline text with variant, truncation, and monospace options.",
};

// ============================================================================
// COMPONENT
// ============================================================================

/**
 * TextWidget - Inline text display with style variants
 *
 * Figma reference: Node 239-14918
 * Styles:
 * - body: 16px Regular Inter, #373D4D, line-height 24px
 * - body-small: 14px Regular Inter, #373D4D (default for L2 containers)
 * - caption: 12px Regular Inter, #64748B (muted-slate), line-height 16px
 */
export const TextWidget: React.FC<TextWidgetProps> = ({
  value,
  variant = "body-small",
  truncate = false,
  monospace = false,
}) => {
  if (!value) return null;

  const variantClasses: Record<string, string> = {
    body: "comet-body text-foreground",
    "body-small": "comet-body-s text-foreground",
    caption: "comet-body-xs text-muted-slate",
  };

  return (
    <span
      className={cn(
        variantClasses[variant] ?? variantClasses["body-small"],
        truncate && "truncate block",
        monospace && "font-mono",
      )}
    >
      {value}
    </span>
  );
};

export default TextWidget;
