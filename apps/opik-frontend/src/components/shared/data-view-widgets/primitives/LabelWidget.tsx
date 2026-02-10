import React from "react";
import { z } from "zod";
import { DynamicString } from "@/lib/data-view";

// ============================================================================
// TYPES
// ============================================================================

export interface LabelWidgetProps {
  text: string;
}

// ============================================================================
// CONFIG (for registry building)
// ============================================================================

export const labelWidgetConfig = {
  type: "Label" as const,
  category: "inline" as const,
  schema: z.object({
    text: DynamicString.describe("Label text"),
  }),
  description:
    "Key name in a key-value pair. MUST be paired with a value widget (Text, Number) inside InlineRow. Never standalone.",
};

// ============================================================================
// COMPONENT
// ============================================================================

/**
 * LabelWidget - Small text descriptor
 *
 * Figma reference: Node 239-15121
 * Style: 14px Regular Inter, #373D4D, line-height 20px (Body/Small)
 *
 * Rules:
 * - Non-interactive
 * - Should not be the only child of a container
 */
export const LabelWidget: React.FC<LabelWidgetProps> = ({ text }) => {
  if (!text) return null;

  return <span className="comet-body-s text-foreground">{text}</span>;
};

export default LabelWidget;
