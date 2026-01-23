import isNumber from "lodash/isNumber";

import { ChartConfig } from "@/components/ui/chart";
import { TAG_VARIANTS_COLOR_MAP } from "@/components/ui/tag";
import { generateTagVariant } from "@/lib/traces";

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
