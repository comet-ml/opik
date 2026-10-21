import React from "react";
import { useParams } from "@tanstack/react-router";
import useLocalStorageState from "use-local-storage-state";
import { useFeatureFlagVariantKey } from "posthog-js/react";

import { useActiveProjectId, useActiveWorkspaceName } from "@/store/AppStore";
import useProjectById from "@/api/projects/useProjectById";
import { DEMO_PROJECT_NAMES } from "@/constants/shared";
import {
  AGENT_ONBOARDING_KEY,
  AGENT_ONBOARDING_STEPS,
  AgentOnboardingState,
  AI_ASSISTED_OPIK_SKILLS_FEATURE_FLAG_KEY,
  DEFAULT_ONBOARDING_FLOW,
} from "@/v2/pages/GetStartedPage/AgentOnboarding/AgentOnboardingContext";

export type DemoProjectVerdict = {
  isDemoProject: boolean;
  // False while the project query is pending, which also reads as "not a demo".
  isSettled: boolean;
};

// Demo membership is by name: nothing on the project marks it.
export const useIsDemoProjectById = (
  projectId?: string | null,
): DemoProjectVerdict => {
  const { data: project, isPending } = useProjectById(
    { projectId: projectId! },
    { enabled: !!projectId },
  );

  return {
    isDemoProject: !!project?.name && DEMO_PROJECT_NAMES.includes(project.name),
    // A disabled query stays pending forever, so no project counts as settled.
    isSettled: !projectId || !isPending,
  };
};

export type DemoProjectBannerVisibility = {
  isBannerVisible: boolean;
  isOnDemoProjectPage: boolean;
  isSettled: boolean;
  // The sticky active project, which outlives the page it was opened from.
  // Onboarding auto-completion keys off this, not off the page.
  isDemoProjectActive: boolean;
  isOnboardingActive: boolean;
  isManualFlow: boolean;
  onboardingState?: AgentOnboardingState;
  setOnboardingState: React.Dispatch<
    React.SetStateAction<AgentOnboardingState | undefined>
  >;
};

// Shared by the demo banner and the MCP announcement, which stands aside for it.
// Visibility follows the page (OPIK-6027: "while browsing the demo project"),
// not the sticky active project.
export const useDemoProjectBannerVisibility =
  (): DemoProjectBannerVisibility => {
    const activeProjectId = useActiveProjectId();
    const workspaceName = useActiveWorkspaceName();

    const routeProjectId = useParams({
      strict: false,
      select: (params: Record<string, string | undefined>) => params.projectId,
    });

    const { isDemoProject: isOnDemoProjectPage, isSettled } =
      useIsDemoProjectById(routeProjectId);
    const { isDemoProject: isDemoProjectActive } =
      useIsDemoProjectById(activeProjectId);

    const [onboardingState, setOnboardingState] =
      useLocalStorageState<AgentOnboardingState>(
        `${AGENT_ONBOARDING_KEY}-${workspaceName}`,
      );

    const variant =
      useFeatureFlagVariantKey(AI_ASSISTED_OPIK_SKILLS_FEATURE_FLAG_KEY) ??
      DEFAULT_ONBOARDING_FLOW;
    const isManualFlow = variant === "manual";

    const isOnboardingActive =
      !!onboardingState?.step &&
      onboardingState.step !== AGENT_ONBOARDING_STEPS.DONE;

    return {
      isBannerVisible:
        isOnDemoProjectPage && (isOnboardingActive || isManualFlow),
      isOnDemoProjectPage,
      isSettled,
      isDemoProjectActive,
      isOnboardingActive,
      isManualFlow,
      onboardingState,
      setOnboardingState,
    };
  };
