import React from "react";
import { z } from "zod";
import { cn } from "@/lib/utils";
import { DynamicString } from "@/lib/data-view";

// ============================================================================
// TYPES
// ============================================================================

export type HeaderLevel = 1 | 2 | 3;

export interface HeaderWidgetProps {
  text: string;
  level?: HeaderLevel;
}

// ============================================================================
// CONFIG (for registry building)
// ============================================================================

export const headerWidgetConfig = {
  type: "Header" as const,
  category: "block" as const,
  schema: z.object({
    text: DynamicString.describe("Header text content"),
    level: z
      .union([z.literal(1), z.literal(2), z.literal(3)])
      .nullable()
      .optional()
      .describe("Heading level (1=largest, 3=smallest)"),
  }),
  description:
    "Section or view title. Cannot be nested inside Level2 container.",
};

// ============================================================================
// COMPONENT
// ============================================================================

/**
 * HeaderWidget - Section/view title with semantic heading levels
 *
 * Figma reference: Node 239-15121
 * Styles:
 * - Level 1: 20px Medium Inter, #373D4D, line-height 28px (comet-title-m)
 * - Level 2: 16px Medium Inter, #373D4D, line-height 24px (comet-body-accented)
 * - Level 3: 14px Medium Inter, #373D4D, line-height 20px (comet-body-s-accented)
 *
 * Constraints:
 * - Cannot be nested inside Level2 container
 */
export const HeaderWidget: React.FC<HeaderWidgetProps> = ({
  text,
  level = 1,
}) => {
  if (!text) return null;

  const levelClasses: Record<HeaderLevel, string> = {
    1: "comet-title-m text-foreground",
    2: "comet-body-accented text-foreground",
    3: "comet-body-s-accented text-foreground",
  };

  const Tag = `h${level}` as const;

  return (
    <Tag className={cn(levelClasses[level] ?? levelClasses[1], "m-0")}>
      {text}
    </Tag>
  );
};

export default HeaderWidget;
