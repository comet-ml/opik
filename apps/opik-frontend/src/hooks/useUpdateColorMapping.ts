import { useCallback } from "react";
import useAppStore from "@/store/AppStore";
import useWorkspaceConfig from "@/api/workspaces/useWorkspaceConfig";
import useWorkspaceConfigMutation from "@/api/workspaces/useWorkspaceConfigMutation";
import { useToast } from "@/components/ui/use-toast";

export const COLOR_MAP_MAX_ENTRIES = 10000;

const useUpdateColorMapping = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { data: workspaceConfig } = useWorkspaceConfig({ workspaceName });
  const { mutate: updateWorkspaceConfig, isPending } =
    useWorkspaceConfigMutation();
  const { toast } = useToast();

  const updateColor = useCallback(
    (label: string, hexColor: string) => {
      const currentMap = workspaceConfig?.color_map ?? {};
      const isExisting = label in currentMap;

      if (
        !isExisting &&
        Object.keys(currentMap).length >= COLOR_MAP_MAX_ENTRIES
      ) {
        toast({
          title: "Color map limit reached",
          description: `Cannot exceed ${COLOR_MAP_MAX_ENTRIES} color mappings.`,
          variant: "destructive",
        });
        return;
      }

      updateWorkspaceConfig({
        config: {
          timeout_to_mark_thread_as_inactive:
            workspaceConfig?.timeout_to_mark_thread_as_inactive ?? null,
          truncation_on_tables: workspaceConfig?.truncation_on_tables ?? null,
          color_map: { ...currentMap, [label]: hexColor },
        },
      });
    },
    [workspaceConfig, updateWorkspaceConfig, toast],
  );

  const resetColor = useCallback(
    (label: string) => {
      const currentMap = { ...(workspaceConfig?.color_map ?? {}) };
      delete currentMap[label];

      updateWorkspaceConfig({
        config: {
          timeout_to_mark_thread_as_inactive:
            workspaceConfig?.timeout_to_mark_thread_as_inactive ?? null,
          truncation_on_tables: workspaceConfig?.truncation_on_tables ?? null,
          color_map: Object.keys(currentMap).length > 0 ? currentMap : null,
        },
      });
    },
    [workspaceConfig, updateWorkspaceConfig],
  );

  return { updateColor, resetColor, isPending };
};

export default useUpdateColorMapping;
