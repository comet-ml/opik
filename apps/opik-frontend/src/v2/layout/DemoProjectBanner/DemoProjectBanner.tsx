import React from "react";
import { Link } from "@tanstack/react-router";

import { useActiveWorkspaceName } from "@/store/AppStore";
import Banner from "@/v2/layout/Banner/Banner";
import { AGENT_ONBOARDING_STEPS } from "@/v2/pages/GetStartedPage/AgentOnboarding/AgentOnboardingContext";
import { DEMO_BANNER_HEIGHT } from "./constants";
import { useDemoProjectBannerVisibility } from "./useDemoProjectBannerVisibility";
import useAutoCompleteAgentOnboarding from "./useAutoCompleteAgentOnboarding";

interface DemoProjectBannerProps {
  onChangeHeight: (height: number) => void;
}

const DemoProjectBanner: React.FC<DemoProjectBannerProps> = ({
  onChangeHeight,
}) => {
  const workspaceName = useActiveWorkspaceName();

  const {
    isBannerVisible,
    isDemoProjectActive,
    isOnboardingActive,
    isManualFlow,
    onboardingState,
    setOnboardingState,
  } = useDemoProjectBannerVisibility();

  // Sticky active project on purpose: this must keep running after the user
  // leaves the demo project.
  useAutoCompleteAgentOnboarding({
    agentName: onboardingState?.agentName,
    enabled: isDemoProjectActive && isOnboardingActive,
  });

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
    <Banner
      visible={isBannerVisible}
      onChangeHeight={onChangeHeight}
      initialHeight={DEMO_BANNER_HEIGHT}
      ariaLabel="Demo project"
      className="h-8 px-4 py-0"
    >
      <span>
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
    </Banner>
  );
};

export default DemoProjectBanner;
