import React from "react";
import { z } from "zod";
import { Tag } from "@/components/ui/tag";
import { DynamicString } from "@/lib/data-view";

// ============================================================================
// TYPES
// ============================================================================

export type TagVariant = "default" | "error" | "warning" | "success" | "info";

export interface TagWidgetProps {
  label: string;
  variant?: TagVariant;
}

// ============================================================================
// CONFIG (for registry building)
// ============================================================================

export const tagWidgetConfig = {
  type: "Tag" as const,
  category: "inline" as const,
  schema: z.object({
    label: DynamicString.describe("Tag label text"),
    variant: z
      .enum(["default", "error", "warning", "success", "info"])
      .nullable()
      .optional()
      .describe("Tag variant for state/severity"),
  }),
  description:
    "Status chip/tag for state, severity, or classification. Examples: error, retrieval, cached, streamed, tool-call.",
};

// ============================================================================
// COMPONENT
// ============================================================================

/**
 * TagWidget - Status chip/tag for state or severity
 *
 * Figma reference: Node 239-15706
 * Variants (mapped to existing Tag component):
 * - default → gray (bg #EBF2F5, text #45575F)
 * - error → red (bg #FFD2D2, text #4E1D1D)
 * - warning → yellow (bg #FEF0C8, text #675523)
 * - success → green (bg #DAFBF0, text #295747)
 * - info → blue (bg #E2EFFD, text #19426B)
 *
 * Style: 14px Medium, px-[6px] py-[2px], rounded-[4px]
 */
export const TagWidget: React.FC<TagWidgetProps> = ({
  label,
  variant = "default",
}) => {
  if (!label) return null;

  // Map widget variants to existing Tag variants
  const variantMap: Record<
    TagVariant,
    "gray" | "red" | "yellow" | "green" | "blue"
  > = {
    default: "gray",
    error: "red",
    warning: "yellow",
    success: "green",
    info: "blue",
  };

  return (
    <Tag variant={variantMap[variant]} size="md">
      {label}
    </Tag>
  );
};

export default TagWidget;
