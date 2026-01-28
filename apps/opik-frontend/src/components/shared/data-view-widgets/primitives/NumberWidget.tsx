import React from "react";
import { z } from "zod";
import { cn } from "@/lib/utils";
import { DynamicNumber, NullableDynamicString } from "@/lib/data-view";

// ============================================================================
// TYPES
// ============================================================================

export type NumberSize = "xs" | "sm" | "md" | "lg" | "xl";
export type NumberFormat = "decimal" | "percent" | "currency";

export interface NumberWidgetProps {
  value: number | null;
  label?: string | null;
  size?: NumberSize;
  format?: NumberFormat;
}

// ============================================================================
// CONFIG (for registry building)
// ============================================================================

export const numberWidgetConfig = {
  type: "Number" as const,
  category: "inline" as const,
  schema: z.object({
    value: DynamicNumber,
    label: NullableDynamicString.describe("Optional label"),
    size: z
      .enum(["xs", "sm", "md", "lg", "xl"])
      .nullable()
      .optional()
      .describe("Text size variant"),
    format: z
      .enum(["decimal", "percent", "currency"])
      .nullable()
      .optional()
      .describe(
        "Number formatting. Note: 'percent' expects decimal input (0.5 = 50%)",
      ),
  }),
  description:
    "Inline number display with size variants and formatting options.",
};

// ============================================================================
// HELPERS
// ============================================================================

/**
 * Formats a number according to the specified format.
 *
 * @param value - The numeric value to format
 * @param format - The format type:
 *   - "decimal": Smart formatting (integers as-is, floats to 2 decimals)
 *   - "percent": Expects decimal input (0.5 = 50%, 0.95 = 95%)
 *     NOTE: Pass 0.5 to display "50.0%", NOT 50
 *   - "currency": USD currency formatting
 *
 * @example
 * formatNumber(0.5, "percent")   // Returns "50.0%"
 * formatNumber(0.95, "percent")  // Returns "95.0%"
 * formatNumber(50, "percent")    // Returns "5000.0%" (probably not what you want!)
 */
function formatNumber(value: number, format: NumberFormat = "decimal"): string {
  switch (format) {
    case "percent":
      // IMPORTANT: Expects decimal input (0.5 = 50%), not whole numbers
      return `${(value * 100).toFixed(1)}%`;
    case "currency":
      return new Intl.NumberFormat("en-US", {
        style: "currency",
        currency: "USD",
      }).format(value);
    case "decimal":
    default:
      // Smart formatting: integers show as-is, floats show up to 2 decimals
      return Number.isInteger(value) ? String(value) : value.toFixed(2);
  }
}

// ============================================================================
// COMPONENT
// ============================================================================

/**
 * NumberWidget - Inline number display with size and format variants
 *
 * Figma reference: Node 239-15116
 * Sizes:
 * - xl: 20px Medium (Title/Medium), line-height 28px
 * - lg: 16px Medium (Body/Accented), line-height 24px
 * - md: 16px Regular (Body/Regular), line-height 24px
 * - sm: 14px Regular (Body/Small), line-height 20px
 * - xs: 12px Regular (Body/XSmall), #64748B (muted-slate), line-height 16px
 */
export const NumberWidget: React.FC<NumberWidgetProps> = ({
  value,
  label,
  size = "md",
  format = "decimal",
}) => {
  if (value === null || value === undefined) return null;

  const sizeClasses: Record<NumberSize, string> = {
    xl: "comet-title-m text-foreground",
    lg: "comet-body-accented text-foreground",
    md: "comet-body text-foreground",
    sm: "comet-body-s text-foreground",
    xs: "comet-body-xs text-muted-slate",
  };

  const formattedValue = formatNumber(value, format);

  return (
    <span className="inline-flex items-baseline gap-1">
      {label && <span className="comet-body-xs text-muted-slate">{label}</span>}
      <span className={cn(sizeClasses[size] ?? sizeClasses.md)}>
        {formattedValue}
      </span>
    </span>
  );
};

export default NumberWidget;
