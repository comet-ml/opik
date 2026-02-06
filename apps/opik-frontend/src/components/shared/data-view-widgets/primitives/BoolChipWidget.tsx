import React from "react";
import { z } from "zod";
import { Tag } from "@/components/ui/tag";
import { DynamicBoolean, NullableDynamicString } from "@/lib/data-view";

// ============================================================================
// TYPES
// ============================================================================

export interface BoolChipWidgetProps {
  value: boolean | null;
  trueLabel?: string | null;
  falseLabel?: string | null;
}

// ============================================================================
// CONFIG (for registry building)
// ============================================================================

export const boolChipWidgetConfig = {
  type: "BoolChip" as const,
  category: "inline" as const,
  schema: z.object({
    value: DynamicBoolean.describe("Boolean value"),
    trueLabel: NullableDynamicString.describe(
      'Label for true state (default: "Yes")',
    ),
    falseLabel: NullableDynamicString.describe(
      'Label for false state (default: "No")',
    ),
  }),
  description:
    "Boolean as a status chip. Good when the value semantically represents a state.",
};

// ============================================================================
// COMPONENT
// ============================================================================

/**
 * BoolChipWidget - Boolean displayed as a status chip
 *
 * Figma reference: Node 240-17129
 * Renders as a chip/tag using the same visual language as TagWidget.
 * - true: green chip
 * - false: gray chip
 */
export const BoolChipWidget: React.FC<BoolChipWidgetProps> = ({
  value,
  trueLabel = "Yes",
  falseLabel = "No",
}) => {
  if (value === null || value === undefined) return null;

  return (
    <Tag variant={value ? "green" : "gray"} size="md">
      {value ? trueLabel ?? "Yes" : falseLabel ?? "No"}
    </Tag>
  );
};

export default BoolChipWidget;
