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
