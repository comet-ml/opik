import React from "react";
import { create } from "zustand";

import WorkspacePreloader from "@/components/shared/WorkspacePreloader/WorkspacePreloader";
import { GoogleColabCardCoreProps } from "@/components/pages-shared/onboarding/GoogleColabCard/GoogleColabCardCore";
import { InviteDevButtonProps } from "@/plugins/comet/InviteDevButton";
import { SidebarInviteDevButtonProps } from "@/plugins/comet/SidebarInviteDevButton";
import { CollaboratorsTabTriggerProps } from "@/plugins/comet/CollaboratorsTabTrigger";

type PluginStore = {
  Logo: React.ComponentType<{ expanded: boolean }> | null;
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
  init: unknown;
  setupPlugins: (folderName: string) => Promise<void>;
};

const VALID_PLUGIN_FOLDER_NAMES = ["comet"];
const PLUGIN_NAMES = [
  "Logo",
  "UserMenu",
  "InviteUsersForm",
  "GetStartedPage",
  "GoogleColabCard",
  "WorkspacePreloader",
  "PermissionsProvider",
  "RetentionBanner",
  "InviteDevButton",
  "SidebarInviteDevButton",
  "SideBarMenuItems",
  "CollaboratorsTab",
  "CollaboratorsTabTrigger",
  "WorkspaceSelector",
  "init",
];

const usePluginsStore = create<PluginStore>((set) => ({
  Logo: null,
  UserMenu: null,
  InviteUsersForm: null,
  GetStartedPage: null,
  GoogleColabCard: null,
  WorkspacePreloader: null,
  PermissionsProvider: null,
  RetentionBanner: null,
  InviteDevButton: null,
  SidebarInviteDevButton: null,
  SideBarMenuItems: null,
  CollaboratorsTab: null,
  CollaboratorsTabTrigger: null,
  WorkspaceSelector: null,
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
  },
}));

export default usePluginsStore;
