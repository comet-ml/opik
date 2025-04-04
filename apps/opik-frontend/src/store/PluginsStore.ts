import React from "react";
import { create } from "zustand";

import WorkspacePreloader from "@/components/shared/WorkspacePreloader/WorkspacePreloader";
import { GoogleColabCardCoreProps } from "@/components/pages-shared/onboarding/GoogleColabCard/GoogleColabCardCore";
import { FrameworkIntegrationsProps } from "@/components/pages-shared/onboarding/FrameworkIntegrations/FrameworkIntegrations";
import { CommentsViewerCoreProps } from "@/components/pages-shared/traces/TraceDetailsPanel/CommentsViewer/CommentsViewerCore";
import { ExperimentCommentsViewerCoreProps } from "@/components/pages/CompareExperimentsPage/CompareExperimentsPanel/DataTab/ExperimentCommentsViewerCore";
import { CreateExperimentCodeCoreProps } from "@/components/pages-shared/onboarding/CreateExperimentCode/CreateExperimentCodeCore";

type PluginStore = {
  Logo: React.ComponentType<{ expanded: boolean }> | null;
  UserMenu: React.ComponentType | null;
  GetStartedPage: React.ComponentType | null;
  WorkspacePreloader: React.ComponentType<{ children: React.ReactNode }> | null;
  FrameworkIntegrations: React.ComponentType<FrameworkIntegrationsProps> | null;
  GoogleColabCard: React.ComponentType<GoogleColabCardCoreProps> | null;
  ApiKeyCard: React.ComponentType | null;
  CreateExperimentCode: React.ComponentType<CreateExperimentCodeCoreProps> | null;
  EvaluationExamples: React.ComponentType | null;
  CommentsViewer: React.ComponentType<CommentsViewerCoreProps> | null;
  ExperimentCommentsViewer: React.ComponentType<ExperimentCommentsViewerCoreProps> | null;
  RetentionBanner: React.ComponentType<{
    onChangeHeight: (height: number) => void;
  }> | null;
  init: unknown;
  setupPlugins: (folderName: string) => Promise<void>;
};

const VALID_PLUGIN_FOLDER_NAMES = ["comet"];
const PLUGIN_NAMES = [
  "Logo",
  "UserMenu",
  "GetStartedPage",
  "FrameworkIntegrations",
  "GoogleColabCard",
  "ApiKeyCard",
  "CreateExperimentCode",
  "CommentsViewer",
  "WorkspacePreloader",
  "EvaluationExamples",
  "ExperimentCommentsViewer",
  "RetentionBanner",
  "init",
];

const usePluginsStore = create<PluginStore>((set) => ({
  Logo: null,
  UserMenu: null,
  GetStartedPage: null,
  FrameworkIntegrations: null,
  GoogleColabCard: null,
  ApiKeyCard: null,
  WorkspacePreloader: null,
  CreateExperimentCode: null,
  CommentsViewer: null,
  EvaluationExamples: null,
  ExperimentCommentsViewer: null,
  RetentionBanner: null,
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
