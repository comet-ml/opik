import React, { useLayoutEffect, useRef } from "react";
import { Link } from "@tanstack/react-router";

import { useActiveWorkspaceName } from "@/store/AppStore";
import { useObserveResizeNode } from "@/hooks/useObserveResizeNode";
import { cn } from "@/lib/utils";
import { AGENT_ONBOARDING_STEPS } from "@/v2/pages/GetStartedPage/AgentOnboarding/AgentOnboardingContext";
import { DEMO_BANNER_HEIGHT, DEMO_BANNER_HEIGHT_CLASS } from "./constants";
import { useDemoProjectBannerVisibility } from "./useDemoProjectBannerVisibility";
import useAutoCompleteAgentOnboarding from "./useAutoCompleteAgentOnboarding";

interface DemoProjectBannerProps {
  onChangeHeight: (height: number) => void;
}

const DemoProjectBanner: React.FC<DemoProjectBannerProps> = ({
  onChangeHeight,
}) => {
  const heightRef = useRef(DEMO_BANNER_HEIGHT);
  const workspaceName = useActiveWorkspaceName();

  const {
    isBannerVisible,
    isDemoProjectActive,
    isOnboardingActive,
    isManualFlow,
    onboardingState,
    setOnboardingState,
  } = useDemoProjectBannerVisibility();

  const { ref } = useObserveResizeNode<HTMLDivElement>((node) => {
    heightRef.current = node.clientHeight;
    onChangeHeight(node.clientHeight);
  });

  // Keyed to the sticky active project, not the page: this watches the user's
  // own project for its first trace and has to keep doing that wherever they
  // navigate, including away from the demo project that started the flow.
  useAutoCompleteAgentOnboarding({
    agentName: onboardingState?.agentName,
    enabled: isDemoProjectActive && isOnboardingActive,
  });

  // Layout effect, and the height known from the styling rather than a
  // measurement: visibility resolves from a query, so the bar appears a beat
  // after mount, and a height published only once a resize observer had run
  // left the layout at 0 with the bar overlapping the content.
  useLayoutEffect(() => {
    onChangeHeight(isBannerVisible ? heightRef.current : 0);
  }, [isBannerVisible, onChangeHeight]);

  if (!isBannerVisible) {
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
      className={cn(
        "z-10 flex items-center justify-center gap-1.5 bg-primary px-4",
        DEMO_BANNER_HEIGHT_CLASS,
      )}
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
