import React from "react";
import { z } from "zod";

// ============================================================================
// TYPES
// ============================================================================

export interface InlineRowWidgetProps {
  children?: React.ReactNode;
}

// ============================================================================
// CONFIG (for registry building)
// ============================================================================

export const inlineRowWidgetConfig = {
  type: "InlineRow" as const,
  category: "container" as const,
  schema: z.object({}),
  description:
    "Horizontal flex container for inline widgets (tags, links, etc.)",
};

// ============================================================================
// COMPONENT
// ============================================================================

/**
 * InlineRow - Horizontal flex container for inline widgets
 *
 * Figma reference: Node 240-17205
 * Styles:
 * - Display: flex row
 * - Gap: 8px (gap-2)
 * - Align: center
 * - Wrap: wrap (for overflow)
 */
export const InlineRowWidget: React.FC<InlineRowWidgetProps> = ({
  children,
}) => {
  return (
    <div className="flex flex-row flex-wrap items-center gap-2">{children}</div>
  );
};

export default InlineRowWidget;
