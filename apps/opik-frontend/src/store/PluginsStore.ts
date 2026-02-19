import React from "react";
import { create } from "zustand";

import WorkspacePreloader from "@/components/shared/WorkspacePreloader/WorkspacePreloader";
import { GoogleColabCardCoreProps } from "@/components/pages-shared/onboarding/GoogleColabCard/GoogleColabCardCore";
import { InviteDevButtonProps } from "@/plugins/comet/InviteDevButton";
import { SidebarInviteDevButtonProps } from "@/plugins/comet/SidebarInviteDevButton";
import { CollaboratorsTabTriggerProps } from "@/plugins/comet/CollaboratorsTabTrigger";
import { WidgetConfigDialogAddStepProps } from "@/components/shared/Dashboard/WidgetConfigDialog/WidgetConfigDialogAddStep/WidgetConfigDialogAddStep";
import { DashboardWidgetGridProps } from "@/components/shared/Dashboard/DashboardSection/DashboardWidgetGrid/DashboardWidgetGrid";
import { SideBarMenuItemsProps } from "@/components/layout/SideBar/SideBarMenuItems";
import { ExperimentsLinkProps } from "@/components/shared/OnboardingOverlay/steps/StartPreference/ExperimentsLink";
import { PromptPageExperimentsTabProps } from "@/components/pages/PromptPage/PromptPageExperimentsTab";
import { DashboardsViewGuardProps } from "@/plugins/comet/DashboardsViewGuard";
import { ViewSelectorProps } from "@/components/pages-shared/dashboards/ViewSelector/ViewSelector";
import { PlaygroundExperimentsLinkProps } from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputActions/PlaygroundExperimentsLink";
import { RunExperimentButtonProps } from "@/components/pages/HomePage/GetStartedSection/RunExperimentButton";
import { DashboardTemplateItemsProps } from "@/components/pages-shared/dashboards/AddEditCloneDashboardDialog/DashboardTemplateItems";

type PluginStore = {
  Logo: React.ComponentType<{ expanded: boolean }> | null;
  UserMenu: React.ComponentType | null;
  InviteUsersForm: React.ComponentType | null;
  GetStartedPage: React.ComponentType | null;
  WorkspacePreloader: React.ComponentType<{ children: React.ReactNode }> | null;
  GoogleColabCard: React.ComponentType<GoogleColabCardCoreProps> | null;
  RetentionBanner: React.ComponentType<{
    onChangeHeight: (height: number) => void;
  }> | null;
  InviteDevButton: React.ComponentType<InviteDevButtonProps> | null;
  SidebarInviteDevButton: React.ComponentType<SidebarInviteDevButtonProps> | null;
  SideBarMenuItems: React.ComponentType<SideBarMenuItemsProps> | null;
  CollaboratorsTab: React.ComponentType | null;
  CollaboratorsTabTrigger: React.ComponentType<CollaboratorsTabTriggerProps> | null;
  WorkspaceSelector: React.ComponentType | null;
  WidgetConfigDialogAddStep: React.ComponentType<WidgetConfigDialogAddStepProps> | null;
  DashboardWidgetGrid: React.ComponentType<DashboardWidgetGridProps> | null;
  EvaluationSection: React.ComponentType | null;
  ExperimentsPageGuard: React.ComponentType | null;
  DashboardsPageGuard: React.ComponentType | null;
  DashboardsViewGuard: React.ComponentType<DashboardsViewGuardProps> | null;
  StartPreferenceExperimentsLink: React.ComponentType<ExperimentsLinkProps> | null;
  PromptPageExperimentsTabTrigger: React.ComponentType | null;
  PromptPageExperimentsTabContent: React.ComponentType<PromptPageExperimentsTabProps> | null;
  ViewSelector: React.ComponentType<ViewSelectorProps> | null;
  PlaygroundExperimentsLink: React.ComponentType<PlaygroundExperimentsLinkProps> | null;
  RunExperimentButton: React.ComponentType<RunExperimentButtonProps> | null;
  DashboardTemplateItems: React.ComponentType<DashboardTemplateItemsProps> | null;
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
  "RetentionBanner",
  "InviteDevButton",
  "SidebarInviteDevButton",
  "SideBarMenuItems",
  "CollaboratorsTab",
  "CollaboratorsTabTrigger",
  "WorkspaceSelector",
  "WidgetConfigDialogAddStep",
  "DashboardWidgetGrid",
  "EvaluationSection",
  "ExperimentsPageGuard",
  "DashboardsPageGuard",
  "DashboardsViewGuard",
  "StartPreferenceExperimentsLink",
  "PromptPageExperimentsTabTrigger",
  "PromptPageExperimentsTabContent",
  "ViewSelector",
  "PlaygroundExperimentsLink",
  "RunExperimentButton",
  "DashboardTemplateItems",
  "init",
];

const usePluginsStore = create<PluginStore>((set) => ({
  Logo: null,
  UserMenu: null,
  InviteUsersForm: null,
  GetStartedPage: null,
  GoogleColabCard: null,
  WorkspacePreloader: null,
  RetentionBanner: null,
  InviteDevButton: null,
  SidebarInviteDevButton: null,
  SideBarMenuItems: null,
  CollaboratorsTab: null,
  CollaboratorsTabTrigger: null,
  WorkspaceSelector: null,
  WidgetConfigDialogAddStep: null,
  DashboardWidgetGrid: null,
  EvaluationSection: null,
  ExperimentsPageGuard: null,
  DashboardsPageGuard: null,
  DashboardsViewGuard: null,
  StartPreferenceExperimentsLink: null,
  PromptPageExperimentsTabTrigger: null,
  PromptPageExperimentsTabContent: null,
  ViewSelector: null,
  PlaygroundExperimentsLink: null,
  RunExperimentButton: null,
  DashboardTemplateItems: null,
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
