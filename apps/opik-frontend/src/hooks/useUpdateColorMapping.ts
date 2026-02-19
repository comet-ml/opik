import { useCallback } from "react";
import { useServerSync } from "@/components/server-sync-provider";
import useWorkspaceConfigMutation from "@/api/workspaces/useWorkspaceConfigMutation";
import { useToast } from "@/components/ui/use-toast";
import { resolveColor, resolveHexColor } from "@/lib/colorVariants";

export const COLOR_MAP_MAX_ENTRIES = 10000;

const useUpdateColorMapping = () => {
  const { config, previewColor, setPreviewColor } = useServerSync();

  const { mutate: updateWorkspaceConfig, isPending } =
    useWorkspaceConfigMutation();
  const { toast } = useToast();

  const updateColor = useCallback(
    (colorKey: string, hexColor: string) => {
      if (!config)
        return toast({
          title: "Changes not saved",
          description:
            "Workspace configuration is not loaded yet. Please try again.",
          variant: "destructive",
        });

      const currentMap = { ...(config.color_map ?? {}) };
      const isDefault =
        hexColor.toLowerCase() ===
        resolveHexColor(resolveColor(colorKey)).toLowerCase();

      if (isDefault) {
        delete currentMap[colorKey];
      } else {
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

        currentMap[colorKey] = hexColor;
      }

      updateWorkspaceConfig({
        config: {
          timeout_to_mark_thread_as_inactive:
            config.timeout_to_mark_thread_as_inactive,
          truncation_on_tables: config.truncation_on_tables,
          color_map: Object.keys(currentMap).length > 0 ? currentMap : null,
        },
      });
    },
    [config, updateWorkspaceConfig, toast],
  );

  return { updateColor, previewColor, setPreviewColor, isPending };
};

export default useUpdateColorMapping;
