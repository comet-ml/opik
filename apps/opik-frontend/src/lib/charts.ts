import { ChartConfig } from "@/components/ui/chart";
import { TAG_VARIANTS_COLOR_MAP } from "@/components/ui/tag";
import { generateTagVariant } from "@/lib/traces";

interface GetDefaultChartYTickWidthArguments {
  values: (number | null)[];
  characterWidth?: number;
  minWidth?: number;
  maxWidth?: number;
  extraSpace?: number;
}

export const getDefaultChartYTickWidth = ({
  values,
  characterWidth = 7,
  minWidth = 26,
  maxWidth = 80,
  extraSpace = 10,
}: GetDefaultChartYTickWidthArguments) => {
  const lengths = values
    .filter((v) => v !== null)
    .map((v) => Math.round(v!).toString().length);

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
