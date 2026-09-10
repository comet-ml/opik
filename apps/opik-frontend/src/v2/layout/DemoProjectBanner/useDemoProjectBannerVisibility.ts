import React from "react";
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

/**
 * Is this project one of the seeded demo projects?
 *
 * Membership is by name — no field on the project marks a demo project — which
 * is why `DEMO_PROJECT_NAMES` is a list rather than a single constant.
 */
export const useIsDemoProjectById = (projectId?: string | null): boolean => {
  const { data: project } = useProjectById(
    { projectId: projectId! },
    { enabled: !!projectId },
  );

  return !!project?.name && DEMO_PROJECT_NAMES.includes(project.name);
};

export type DemoProjectBannerVisibility = {
  isDemoProject: boolean;
  isOnboardingActive: boolean;
  isManualFlow: boolean;
  isBannerVisible: boolean;
  onboardingState?: AgentOnboardingState;
  setOnboardingState: React.Dispatch<
    React.SetStateAction<AgentOnboardingState | undefined>
  >;
};

/**
 * The single source of truth for whether the demo-project banner is on screen.
 *
 * Two banners need this answer: the demo banner itself, and the MCP
 * announcement banner, which must stand aside for it. Deriving it in both
 * places is how they drift into showing together or hiding together.
 *
 * Note the reach of this verdict: the active project is sticky (persisted per
 * workspace, only cleared on deletion), so it describes "the project you are
 * working in", not "the project on screen" — the demo banner is app-wide while
 * a demo project is active, and so is any suppression keyed on it.
 */
export const useDemoProjectBannerVisibility =
  (): DemoProjectBannerVisibility => {
    const activeProjectId = useActiveProjectId();
    const workspaceName = useActiveWorkspaceName();

    const isDemoProject = useIsDemoProjectById(activeProjectId);

    const [onboardingState, setOnboardingState] =
      useLocalStorageState<AgentOnboardingState>(
        `${AGENT_ONBOARDING_KEY}-${workspaceName}`,
      );

    // Unresolved (PostHog unavailable, or flags not loaded yet) falls back to
    // the manual flow, matching every other call site of this flag.
    const variant =
      useFeatureFlagVariantKey(AI_ASSISTED_OPIK_SKILLS_FEATURE_FLAG_KEY) ??
      DEFAULT_ONBOARDING_FLOW;
    const isManualFlow = variant === "manual";

    const isOnboardingActive =
      !!onboardingState?.step &&
      onboardingState.step !== AGENT_ONBOARDING_STEPS.DONE;

    return {
      isDemoProject,
      isOnboardingActive,
      isManualFlow,
      isBannerVisible: isDemoProject && (isOnboardingActive || isManualFlow),
      onboardingState,
      setOnboardingState,
    };
  };
