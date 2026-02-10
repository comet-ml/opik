import React from "react";
import { z } from "zod";

// ============================================================================
// TYPES
// ============================================================================

export type DividerWidgetProps = Record<string, never>;

// ============================================================================
// CONFIG (for registry building)
// ============================================================================

export const dividerWidgetConfig = {
  type: "Divider" as const,
  category: "block" as const,
  schema: z.object({}),
  description:
    "Visual separator. Cannot appear inside Level2 container or between Input and Output.",
};

// ============================================================================
// COMPONENT
// ============================================================================

/**
 * DividerWidget - Lightweight visual separation line
 *
 * Figma reference: Node 239-16768
 * Styles:
 * - Border color: #E2E8F0 (border-border)
 * - Height: 1px horizontal line
 *
 * Constraints:
 * - Cannot appear inside Level2 container
 * - Cannot separate Input and Output blocks
 */
export const DividerWidget: React.FC<DividerWidgetProps> = () => {
  return <hr className="my-4 border-t border-border" />;
};

export default DividerWidget;
