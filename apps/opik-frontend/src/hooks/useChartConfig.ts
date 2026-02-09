import { useMemo } from "react";
import { ChartConfig } from "@/lib/charts";
import useWorkspaceColorMap from "@/hooks/useWorkspaceColorMap";

export default function useChartConfig(
  lines: string[],
  labelsMap?: Record<string, string>,
  predefinedColorMap?: Record<string, string>,
) {
  const { getChartColorMap } = useWorkspaceColorMap();

  return useMemo(() => {
    const colorMap = getChartColorMap(lines, predefinedColorMap);
    return lines.reduce<ChartConfig>((acc, line) => {
      acc[line] = {
        label: labelsMap?.[line] ?? line,
        color: colorMap[line],
      };
      return acc;
    }, {});
  }, [lines, labelsMap, predefinedColorMap, getChartColorMap]);
}
