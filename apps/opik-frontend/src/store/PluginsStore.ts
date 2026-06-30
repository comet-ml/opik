import React from "react";
import { create } from "zustand";
import { type AnyRoute } from "@tanstack/react-router";

import WorkspacePreloader from "@/shared/WorkspacePreloader/WorkspacePreloader";
import { GoogleColabCardCoreProps } from "@/types/shared";
import { InviteDevButtonProps } from "@/plugins/comet/InviteDevButton";
import { SidebarInviteDevButtonProps } from "@/plugins/comet/SidebarInviteDevButton";
import { CollaboratorsTabTriggerProps } from "@/plugins/comet/CollaboratorsTabTrigger";
import { BridgeSurface, ExplainButtonProps } from "@/types/assistant-sidebar";
import {
  type PluginManifest,
  type PluginRouteParents,
  type PluginSidebarSection,
} from "@/lib/plugins";

type PluginStore = {
  UserMenu: React.ComponentType | null;
  InviteUsersForm: React.ComponentType | null;
  GetStartedPage: React.ComponentType | null;
  WorkspacePreloader: React.ComponentType<{ children: React.ReactNode }> | null;
  PermissionsProvider: React.ComponentType<{
    children: React.ReactNode;
  }> | null;
  LayoutProvider: React.ComponentType<{ children: React.ReactNode }> | null;
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
  ExplainButton: React.ComponentType<ExplainButtonProps> | null;
  AssistantPrewarmer: React.ComponentType | null;
  AssistantDebugInfo: React.ComponentType | null;
  UpgradeButton: React.ComponentType | null;
  BillingLink: React.ComponentType | null;
  init: unknown;
  collectRoutes: (parents: PluginRouteParents) => AnyRoute[];
  sidebarSections: PluginSidebarSection[];
  hasPlugin: (name: string) => boolean;
  setupPlugins: () => Promise<void>;
};

const PLUGIN_NAMES = [
  "UserMenu",
  "InviteUsersForm",
  "GetStartedPage",
  "GoogleColabCard",
  "WorkspacePreloader",
  "PermissionsProvider",
  "LayoutProvider",
  "RetentionBanner",
  "InviteDevButton",
  "SidebarInviteDevButton",
  "CollaboratorsTab",
  "CollaboratorsTabTrigger",
  "WorkspaceSelector",
  "SidebarWorkspaceSelector",
  "AssistantSidebar",
  "ExplainButton",
  "AssistantPrewarmer",
  "AssistantDebugInfo",
  "UpgradeButton",
  "BillingLink",
  "init",
];

const ACTIVE_PLUGINS = (() => {
  const configured = import.meta.env.VITE_FE_PLUGINS as string | undefined;
  if (configured) {
    return configured
      .split(",")
      .map((name) => name.trim())
      .filter(Boolean);
  }
  return [import.meta.env.MODE];
})();

const manifestModules = import.meta.glob("../plugins/*/manifest.ts", {
  eager: true,
});

const ACTIVE_MANIFESTS: PluginManifest[] = Object.values(manifestModules)
  .map((mod) => (mod as { default?: PluginManifest }).default)
  .filter(
    (manifest): manifest is PluginManifest =>
      Boolean(manifest) && ACTIVE_PLUGINS.includes(manifest!.name),
  );

const usePluginsStore = create<PluginStore>((set) => ({
  UserMenu: null,
  InviteUsersForm: null,
  GetStartedPage: null,
  GoogleColabCard: null,
  WorkspacePreloader: null,
  PermissionsProvider: null,
  LayoutProvider: null,
  RetentionBanner: null,
  InviteDevButton: null,
  SidebarInviteDevButton: null,
  CollaboratorsTab: null,
  CollaboratorsTabTrigger: null,
  WorkspaceSelector: null,
  SidebarWorkspaceSelector: null,
  AssistantSidebar: null,
  ExplainButton: null,
  AssistantPrewarmer: null,
  AssistantDebugInfo: null,
  UpgradeButton: null,
  BillingLink: null,
  init: null,
  collectRoutes: (parents) =>
    ACTIVE_MANIFESTS.flatMap((manifest) => manifest.routes?.(parents) ?? []),
  sidebarSections: ACTIVE_MANIFESTS.map((manifest) => manifest.sidebar).filter(
    (sidebar): sidebar is PluginSidebarSection => Boolean(sidebar),
  ),
  hasPlugin: (name) =>
    ACTIVE_MANIFESTS.some((manifest) => manifest.name === name),
  setupPlugins: async () => {
    if (ACTIVE_PLUGINS.length === 0) {
      return set({ WorkspacePreloader });
    }

    for (const folderName of ACTIVE_PLUGINS) {
      await Promise.all(
        PLUGIN_NAMES.map(async (pluginName) => {
          try {
            // dynamic import does not support alias
            const plugin = await import(
              `../plugins/${folderName}/${pluginName}.tsx`
            );

            if (plugin.default) {
              set({ [pluginName]: plugin.default });
            }
          } catch {
            // plugin file is optional — swallow and continue
          }
        }),
      );
    }

    // Ensure WorkspacePreloader is always set (fallback to default)
    if (!usePluginsStore.getState().WorkspacePreloader) {
      set({ WorkspacePreloader });
    }
  },
}));

export default usePluginsStore;
