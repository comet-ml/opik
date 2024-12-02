import { ChartConfig } from "@/components/ui/chart";
import { TAG_VARIANTS_COLOR_MAP } from "@/components/ui/tag";
import { generateTagVariant } from "@/lib/traces";

const DEFAULT_TICK_PRECISION = 6;

interface GetDefaultChartYTickWidthArguments {
  values: (number | null)[];
  characterWidth?: number;
  minWidth?: number;
  maxWidth?: number;
  extraSpace?: number;
  tickPrecision?: number;
  withDecimals?: boolean;
}

export const getDefaultChartYTickWidth = ({
  values,
  characterWidth = 7,
  minWidth = 26,
  maxWidth = 80,
  extraSpace = 10,
  tickPrecision = DEFAULT_TICK_PRECISION,
  withDecimals = false,
}: GetDefaultChartYTickWidthArguments) => {
  const lengths = values
    .filter((v) => v !== null)
    .map((v) => {
      if (withDecimals) {
        return v?.toFixed(tickPrecision).toString().length || 0;
      }

      return Math.round(v!).toString().length;
    });

  return Math.min(
    Math.max(minWidth, Math.max(...lengths) * characterWidth + extraSpace),
    maxWidth,
  );
};

export const getDefaultHashedColorsChartConfig = (lines: string[]) => {
  return lines.reduce<ChartConfig>((acc, line) => {
    acc[line] = {
      label: line,
      color: TAG_VARIANTS_COLOR_MAP[generateTagVariant(line)!],
    };
    return acc;
  }, {});
};
