import isNumber from "lodash/isNumber";

import { ChartConfig } from "@/components/ui/chart";
import { TAG_VARIANTS_COLOR_MAP } from "@/components/ui/tag";
import { generateTagVariant } from "@/lib/traces";

/**
 * Ordered list of colors for breakdown groups to ensure visual distinction.
 * Colors are ordered to maximize visual contrast between adjacent items.
 */
const BREAKDOWN_GROUP_COLORS = [
  "var(--color-blue)",
  "var(--color-orange)",
  "var(--color-green)",
  "var(--color-purple)",
  "var(--color-pink)",
  "var(--color-turquoise)",
  "var(--color-yellow)",
  "var(--color-burgundy)",
  "var(--color-gray)",
  "var(--color-primary)",
];

/**
 * Generate a color map for breakdown groups ensuring each group gets a distinct color.
 * Groups are sorted alphabetically to guarantee consistent color assignment across renders.
 */
export const generateBreakdownColorMap = (
  groupNames: string[],
): Record<string, string> => {
  // Sort alphabetically for consistent color assignment
  const sortedGroups = [...groupNames].sort((a, b) =>
    a.localeCompare(b, undefined, { sensitivity: "base" }),
  );

  const colorMap: Record<string, string> = {};
  sortedGroups.forEach((groupName, index) => {
    // Use modulo to cycle through colors if we have more groups than colors
    colorMap[groupName] =
      BREAKDOWN_GROUP_COLORS[index % BREAKDOWN_GROUP_COLORS.length];
  });

  return colorMap;
};

export const getDefaultHashedColorsChartConfig = (
  lines: string[],
  labelsMap?: Record<string, string>,
  predefinedColorMap: Record<string, string> = {},
) => {
  return lines.reduce<ChartConfig>((acc, line) => {
    acc[line] = {
      label: labelsMap?.[line] ?? line,
      color:
        predefinedColorMap[line] ||
        TAG_VARIANTS_COLOR_MAP[generateTagVariant(line)!],
    };
    return acc;
  }, {});
};

type CalculateChartTruncateLength = {
  width: number;
  minWidth?: number;
  minLength?: number;
  charsPerWidth?: number;
};
export const calculateChartTruncateLength = ({
  width,
  minWidth = 400,
  minLength = 14,
  charsPerWidth = 0.35,
}: CalculateChartTruncateLength) => {
  if (width <= minWidth) {
    return minLength;
  }

  const extraWidth = width - minWidth;
  const extraChars = Math.floor(extraWidth * charsPerWidth);

  return minLength + extraChars;
};

export const truncateChartLabel = (value: string, maxLength: number = 14) => {
  return value.length > maxLength ? `${value.slice(0, maxLength)}...` : value;
};

type ChartDataPoint = Record<string, number | string | null>;

export const extractChartValues = (
  data: ChartDataPoint[],
  config: ChartConfig,
): number[] => {
  const keys = Object.keys(config);
  return data.flatMap((point) =>
    keys.map((key) => point[key]).filter((v): v is number => isNumber(v)),
  );
};
