import { ChartConfig } from "@/components/ui/chart";
import { TAG_VARIANTS_COLOR_MAP } from "@/components/ui/tag";
import { generateTagVariant } from "@/lib/traces";

// Using explicit colors instead of CSS variables
const EXPERIMENT_COLORS = [
  "#5899DA", // blue
  "#E8743B", // orange
  "#19A979", // green
  "#ED4A7B", // red
  "#945ECF", // purple
  "#13A4B4", // cyan
];

export const getExperimentColorsConfig = (experiments: string[]) => {
  return experiments.reduce(
    (acc, exp, index) => {
      acc[exp] = {
        color: EXPERIMENT_COLORS[index % EXPERIMENT_COLORS.length],
        label: exp,
      };
      return acc;
    },
    {} as Record<string, { color: string; label: string }>,
  );
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
