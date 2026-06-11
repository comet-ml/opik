import { TagProps } from "@/ui/tag";
import {
  ISSUE_CATEGORY,
  ISSUE_SEVERITY,
  SignalsStatValue,
} from "@/types/signals";

export const SEVERITY_LABEL_MAP: Record<ISSUE_SEVERITY, string> = {
  [ISSUE_SEVERITY.high]: "High",
  [ISSUE_SEVERITY.medium]: "Medium",
  [ISSUE_SEVERITY.low]: "Low",
};

// Dot color used next to the severity label in the list / detail header.
// high → --destructive (#F14668), medium → --chart-yellow (#F4B400),
// low → #6C8893 (no design-system token, using the design hex).
export const SEVERITY_DOT_COLOR_MAP: Record<ISSUE_SEVERITY, string> = {
  [ISSUE_SEVERITY.high]: "hsl(var(--destructive))",
  [ISSUE_SEVERITY.medium]: "var(--chart-yellow)",
  [ISSUE_SEVERITY.low]: "#6C8893",
};

export const SEVERITY_TAG_VARIANT_MAP: Record<
  ISSUE_SEVERITY,
  TagProps["variant"]
> = {
  [ISSUE_SEVERITY.high]: "red",
  [ISSUE_SEVERITY.medium]: "orange",
  [ISSUE_SEVERITY.low]: "gray",
};

export const CATEGORY_LABEL_MAP: Record<ISSUE_CATEGORY, string> = {
  [ISSUE_CATEGORY.tool_failure]: "Tool failure",
  [ISSUE_CATEGORY.hallucination]: "Hallucination",
  [ISSUE_CATEGORY.timeout]: "Timeout",
  [ISSUE_CATEGORY.quality]: "Quality",
  [ISSUE_CATEGORY.cost]: "Cost",
  [ISSUE_CATEGORY.other]: "Other",
};

export const formatRate = (rate: number): string => {
  const percent = rate * 100;
  const digits = percent < 1 ? 2 : 1;
  return `${percent.toFixed(digits)}%`;
};

export type StatTrend = {
  label: string;
  // semantic mood drives the badge color
  mood: "positive" | "negative" | "neutral";
};

export const getStatTrend = (stat: SignalsStatValue): StatTrend | null => {
  if (stat.trend === undefined || stat.trend === null) return null;

  const isPercentage = stat.trend_type === "percentage";
  const sign = stat.trend > 0 ? "+" : "";
  const label = isPercentage
    ? `${sign}${Math.round(stat.trend * 100)}%`
    : `${sign}${stat.trend}`;

  let mood: StatTrend["mood"] = "neutral";
  if (stat.trend !== 0) {
    const increased = stat.trend > 0;
    const increaseIsGood = stat.trend_direction_positive ?? true;
    mood = increased === increaseIsGood ? "positive" : "negative";
  }

  return { label, mood };
};
