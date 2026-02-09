import { useCallback, useMemo } from "react";
import useWorkspaceConfig from "@/api/workspaces/useWorkspaceConfig";
import useAppStore from "@/store/AppStore";
import { resolveColor, resolveChartColorMap } from "@/lib/colorVariants";

const useWorkspaceColorMap = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { data: workspaceConfig } = useWorkspaceConfig({
    workspaceName,
  });

  const colorMap = workspaceConfig?.color_map ?? null;

  const getColor = useCallback(
    (label: string, customColorMap?: Record<string, string> | null) =>
      resolveColor(label, colorMap, customColorMap),
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

export type WorkspaceColorMap = Record<string, string> | null;

export default useWorkspaceColorMap;
