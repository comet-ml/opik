import { useCallback, useMemo } from "react";
import { useServerSync } from "@/components/server-sync-provider";
import { resolveColor, resolveChartColorMap } from "@/lib/colorVariants";

const useWorkspaceColorMap = () => {
  const { colorMap } = useServerSync();

  const getColor = useCallback(
    (colorKey: string, customColorMap?: Record<string, string> | null) =>
      resolveColor(colorKey, colorMap, customColorMap),
    [colorMap],
  );

  const getChartColorMap = useCallback(
    (labels: string[], customColorMap?: Record<string, string> | null) =>
      resolveChartColorMap(labels, colorMap, customColorMap),
    [colorMap],
  );

  return useMemo(
    () => ({
      colorMap,
      getColor,
      getChartColorMap,
    }),
    [colorMap, getColor, getChartColorMap],
  );
};

export default useWorkspaceColorMap;
