import React from "react";
import { create } from "zustand";

import WorkspacePreloader from "@/shared/WorkspacePreloader/WorkspacePreloader";
import { GoogleColabCardCoreProps } from "@/types/shared";
import { InviteDevButtonProps } from "@/plugins/comet/InviteDevButton";
import { SidebarInviteDevButtonProps } from "@/plugins/comet/SidebarInviteDevButton";
import { CollaboratorsTabTriggerProps } from "@/plugins/comet/CollaboratorsTabTrigger";
import { BridgeSurface } from "@/types/assistant-sidebar";

type PluginStore = {
  UserMenu: React.ComponentType | null;
  InviteUsersForm: React.ComponentType | null;
  GetStartedPage: React.ComponentType | null;
  WorkspacePreloader: React.ComponentType<{ children: React.ReactNode }> | null;
  PermissionsProvider: React.ComponentType<{
    children: React.ReactNode;
  }> | null;
  GoogleColabCard: React.ComponentType<GoogleColabCardCoreProps> | null;
  RetentionBanner: React.ComponentType<{
    onChangeHeight: (height: number) => void;
  }> | null;
  InviteDevButton: React.ComponentType<InviteDevButtonProps> | null;
  SidebarInviteDevButton: React.ComponentType<SidebarInviteDevButtonProps> | null;
  CollaboratorsTab: React.ComponentType | null;
  CollaboratorsTabTrigger: React.ComponentType<CollaboratorsTabTriggerProps> | null;
  WorkspaceSelector: React.ComponentType | null;
  SidebarWorkspaceSelector: React.ComponentType<{ expanded?: boolean }> | null;
  AssistantSidebar: React.ComponentType<{
    surface?: BridgeSurface;
    onWidthChange: (width: number) => void;
  }> | null;
  AssistantPrewarmer: React.ComponentType | null;
  UpgradeButton: React.ComponentType | null;
  init: unknown;
  setupPlugins: (folderName: string) => Promise<void>;
};

const VALID_PLUGIN_FOLDER_NAMES = ["comet", "development"];
const PLUGIN_NAMES = [
  "UserMenu",
  "InviteUsersForm",
  "GetStartedPage",
  "GoogleColabCard",
  "WorkspacePreloader",
  "PermissionsProvider",
  "RetentionBanner",
  "InviteDevButton",
  "SidebarInviteDevButton",
  "CollaboratorsTab",
  "CollaboratorsTabTrigger",
  "WorkspaceSelector",
  "SidebarWorkspaceSelector",
  "AssistantSidebar",
  "AssistantPrewarmer",
  "UpgradeButton",
  "init",
];

const usePluginsStore = create<PluginStore>((set) => ({
  UserMenu: null,
  InviteUsersForm: null,
  GetStartedPage: null,
  GoogleColabCard: null,
  WorkspacePreloader: null,
  PermissionsProvider: null,
  RetentionBanner: null,
  InviteDevButton: null,
  SidebarInviteDevButton: null,
  CollaboratorsTab: null,
  CollaboratorsTabTrigger: null,
  WorkspaceSelector: null,
  SidebarWorkspaceSelector: null,
  AssistantSidebar: null,
  AssistantPrewarmer: null,
  UpgradeButton: null,
  init: null,
  setupPlugins: async (folderName: string) => {
    if (!VALID_PLUGIN_FOLDER_NAMES.includes(folderName)) {
      return set({ WorkspacePreloader });
    }

    for (const pluginName of PLUGIN_NAMES) {
      try {
        // dynamic import does not support alias
        const plugin = await import(
          `../plugins/${folderName}/${pluginName}.tsx`
        );

        if (plugin.default) {
          set({ [pluginName]: plugin.default });
        }
      } catch (error) {
        continue;
      }
    }

    // Ensure WorkspacePreloader is always set (fallback to default)
    if (!usePluginsStore.getState().WorkspacePreloader) {
      set({ WorkspacePreloader });
    }
  },
}));

export default usePluginsStore;
