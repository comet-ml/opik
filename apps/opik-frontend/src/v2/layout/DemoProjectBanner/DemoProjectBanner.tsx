import React, { useEffect, useRef } from "react";
import { Link } from "@tanstack/react-router";
import useLocalStorageState from "use-local-storage-state";
import { useFeatureFlagVariantKey } from "posthog-js/react";

import { useActiveProjectId, useActiveWorkspaceName } from "@/store/AppStore";
import { useObserveResizeNode } from "@/hooks/useObserveResizeNode";
import useProjectById from "@/api/projects/useProjectById";
import { DEMO_PROJECT_NAME } from "@/constants/shared";
import {
  AGENT_ONBOARDING_KEY,
  AGENT_ONBOARDING_STEPS,
  AgentOnboardingState,
  AI_ASSISTED_OPIK_SKILLS_FEATURE_FLAG_KEY,
  DEFAULT_ONBOARDING_FLOW,
} from "@/v2/pages/GetStartedPage/AgentOnboarding/AgentOnboardingContext";
import useAutoCompleteAgentOnboarding from "./useAutoCompleteAgentOnboarding";

interface DemoProjectBannerProps {
  onChangeHeight: (height: number) => void;
}

const DemoProjectBanner: React.FC<DemoProjectBannerProps> = ({
  onChangeHeight,
}) => {
  const heightRef = useRef(0);
  const activeProjectId = useActiveProjectId();
  const workspaceName = useActiveWorkspaceName();

  const { data: project } = useProjectById(
    { projectId: activeProjectId! },
    { enabled: !!activeProjectId },
  );

  const [onboardingState, setOnboardingState] =
    useLocalStorageState<AgentOnboardingState>(
      `${AGENT_ONBOARDING_KEY}-${workspaceName}`,
    );

  const { ref } = useObserveResizeNode<HTMLDivElement>((node) => {
    heightRef.current = node.clientHeight;
    onChangeHeight(node.clientHeight);
  });

  const variant =
    useFeatureFlagVariantKey(AI_ASSISTED_OPIK_SKILLS_FEATURE_FLAG_KEY) ??
    DEFAULT_ONBOARDING_FLOW;
  const isManualFlow = variant === "manual";

  const isDemoProject = project?.name === DEMO_PROJECT_NAME;
  const isOnboardingActive =
    !!onboardingState?.step &&
    onboardingState.step !== AGENT_ONBOARDING_STEPS.DONE;

  useAutoCompleteAgentOnboarding({
    agentName: onboardingState?.agentName,
    enabled: isDemoProject && isOnboardingActive,
  });

  const hideBanner = !isDemoProject || (!isOnboardingActive && !isManualFlow);

  useEffect(() => {
    onChangeHeight(!hideBanner ? heightRef.current : 0);
  }, [hideBanner, onChangeHeight]);

  if (hideBanner) {
    return null;
  }

  // Send the user back to Step 1 (project naming) instead of resuming whichever
  // onboarding step they were on. Pre-fill is preserved by keeping agentName —
  // AgentNameStep initializes its input from it and the create-project mutation
  // auto-advances on 409 when the name still belongs to their existing project.
  const handleCreateYourOwn = () => {
    if (!isManualFlow) {
      setOnboardingState((prev) =>
        prev ? { ...prev, step: AGENT_ONBOARDING_STEPS.AGENT_NAME } : prev,
      );
    }
  };

  return (
    <div
      ref={ref}
      className="z-10 flex h-8 items-center justify-center gap-1.5 bg-primary px-4"
    >
      <span className="comet-body-xs text-center text-white">
        You are viewing a demo project,{" "}
        <Link
          to="/$workspaceName/get-started"
          params={{ workspaceName }}
          onClick={handleCreateYourOwn}
          className="text-white underline underline-offset-2"
        >
          click here to create your own
        </Link>
        .
      </span>
    </div>
  );
};

export default DemoProjectBanner;
