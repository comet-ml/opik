import React from "react";
import { z } from "zod";

// ============================================================================
// TYPES
// ============================================================================

export interface StatItem {
  label: string;
  value: string | number;
}

export interface StatsRowWidgetProps {
  items: StatItem[];
}

// ============================================================================
// CONFIG (for registry building)
// ============================================================================

export const statsRowWidgetConfig = {
  type: "StatsRow" as const,
  category: "inline" as const,
  schema: z.object({
    items: z
      .array(
        z.object({
          label: z.string().describe("Stat label (sentence case)"),
          value: z
            .union([z.string(), z.number()])
            .describe("Stat value (string or number)"),
        }),
      )
      .describe("Array of label-value pairs to display"),
  }),
  description:
    "Inline stats display with bullet separator. Format: Label: Value \u2022 Label: Value. Use for metadata like tokens, duration, cost.",
};

// ============================================================================
// HELPERS
// ============================================================================

/**
 * Capitalizes the first letter of a string (sentence case).
 */
const capitalize = (str: string): string =>
  str.charAt(0).toUpperCase() + str.slice(1);

/**
 * Formats a numeric value for display.
 * - Integers are displayed as-is
 * - Floats are displayed with up to 2 decimal places
 */
const formatValue = (value: string | number): string => {
  if (typeof value === "string") {
    return value;
  }
  // Check if it's an integer
  if (Number.isInteger(value)) {
    return String(value);
  }
  // Float: format with up to 2 decimals
  return value.toFixed(2);
};

// ============================================================================
// COMPONENT
// ============================================================================

/**
 * StatsRowWidget - Inline stats display with bullet separator
 *
 * Renders stats in format: "Label: Value \u2022 Label: Value \u2022 Label: Value"
 *
 * Features:
 * - Labels are automatically sentence-cased (first letter uppercase)
 * - Space after colon
 * - Bullet (\u2022) separator between items
 * - Numbers: integers as-is, floats to 2 decimal places
 * - Default text weight (no bold)
 */
export const StatsRowWidget: React.FC<StatsRowWidgetProps> = ({ items }) => {
  if (!items || items.length === 0) return null;

  return (
    <span className="comet-body-s text-muted-slate">
      {items.map((item, index) => (
        <React.Fragment key={index}>
          {index > 0 && <span className="mx-1.5">&bull;</span>}
          <span>
            {capitalize(item.label)}: {formatValue(item.value)}
          </span>
        </React.Fragment>
      ))}
    </span>
  );
};

export default StatsRowWidget;
