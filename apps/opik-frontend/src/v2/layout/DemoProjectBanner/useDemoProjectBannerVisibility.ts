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
  /**
   * Whether the answer above is the real one yet. It comes from a query, so
   * "not a demo project" and "we do not know yet" are the same `false` — a
   * distinction that matters to anything counting impressions, which must not
   * count a user it is about to hide a banner from.
   */
  isSettled: boolean;
};

/**
 * Is this project one of the seeded demo projects?
 *
 * Membership is by name — no field on the project marks a demo project — which
 * is why `DEMO_PROJECT_NAMES` is a list rather than a single constant.
 */
export const useIsDemoProjectById = (
  projectId?: string | null,
): DemoProjectVerdict => {
  const { data: project, isPending } = useProjectById(
    { projectId: projectId! },
    { enabled: !!projectId },
  );

  return {
    isDemoProject: !!project?.name && DEMO_PROJECT_NAMES.includes(project.name),
    // No project to resolve is a settled answer, not a pending one: a disabled
    // query stays "pending" forever.
    isSettled: !projectId || !isPending,
  };
};

export type DemoProjectBannerVisibility = {
  /** Whether the demo-project banner belongs on screen right now. */
  isBannerVisible: boolean;
  /** Whether the page being viewed belongs to a seeded demo project. */
  isOnDemoProjectPage: boolean;
  /** Whether the page's demo verdict has resolved; see DemoProjectVerdict. */
  isSettled: boolean;
  /**
   * Whether the *sticky* active project is a demo project. This outlives the
   * page it was opened from, so it answers "which project is this user working
   * in", not "what is on screen". Onboarding auto-completion needs the former.
   */
  isDemoProjectActive: boolean;
  isOnboardingActive: boolean;
  isManualFlow: boolean;
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
 * Visibility follows the page, which is what OPIK-6027 asked for ("while
 * browsing the demo project") and what OPIK-6192 extended to the manual
 * "skip and explore" flow. It deliberately does not follow the active project:
 * that id is persisted per workspace and cleared only on deletion, so keying
 * the bar to it kept the bar on screen across the whole app long after the
 * user had left the demo project.
 */
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
