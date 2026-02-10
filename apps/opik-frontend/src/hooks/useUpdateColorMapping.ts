import { useCallback } from "react";
import { useServerSync } from "@/components/server-sync-provider";
import useWorkspaceConfigMutation from "@/api/workspaces/useWorkspaceConfigMutation";
import { useToast } from "@/components/ui/use-toast";

export const COLOR_MAP_MAX_ENTRIES = 10000;

const useUpdateColorMapping = () => {
  const { config, previewColor, setPreviewColor } = useServerSync();

  const { mutate: updateWorkspaceConfig, isPending } =
    useWorkspaceConfigMutation();
  const { toast } = useToast();

  const showConfigNotLoaded = useCallback(() => {
    toast({
      title: "Changes not saved",
      description:
        "Workspace configuration is not loaded yet. Please try again.",
      variant: "destructive",
    });
  }, [toast]);

  const updateColor = useCallback(
    (colorKey: string, hexColor: string) => {
      if (!config) return showConfigNotLoaded();

      const currentMap = config.color_map ?? {};
      const isExisting = colorKey in currentMap;

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
            config.timeout_to_mark_thread_as_inactive,
          truncation_on_tables: config.truncation_on_tables,
          color_map: { ...currentMap, [colorKey]: hexColor },
        },
      });
    },
    [config, updateWorkspaceConfig, toast, showConfigNotLoaded],
  );

  const resetColor = useCallback(
    (colorKey: string) => {
      if (!config) return showConfigNotLoaded();

      const currentMap = { ...(config.color_map ?? {}) };
      delete currentMap[colorKey];

      updateWorkspaceConfig({
        config: {
          timeout_to_mark_thread_as_inactive:
            config.timeout_to_mark_thread_as_inactive,
          truncation_on_tables: config.truncation_on_tables,
          color_map: Object.keys(currentMap).length > 0 ? currentMap : null,
        },
      });
    },
    [config, updateWorkspaceConfig, showConfigNotLoaded],
  );

  return { updateColor, resetColor, previewColor, setPreviewColor, isPending };
};

export default useUpdateColorMapping;
