import isNumber from "lodash/isNumber";

import { ChartConfig } from "@/components/ui/chart";
export type { ChartConfig };

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
