import React, { useEffect, useRef } from "react";
import { Link } from "@tanstack/react-router";
import useLocalStorageState from "use-local-storage-state";
import { PlugZap } from "lucide-react";

import { useActiveProjectId, useActiveWorkspaceName } from "@/store/AppStore";
import { useObserveResizeNode } from "@/hooks/useObserveResizeNode";
import useProjectById from "@/api/projects/useProjectById";
import { DEMO_PROJECT_NAME } from "@/constants/shared";
import {
  AGENT_ONBOARDING_KEY,
  AGENT_ONBOARDING_STEPS,
} from "@/v2/pages/GetStartedPage/AgentOnboarding/AgentOnboardingContext";

interface DemoProjectBannerProps {
  onChangeHeight: (height: number) => void;
}

interface AgentOnboardingState {
  step: string | null;
  agentName: string;
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

  const [onboardingState] =
    useLocalStorageState<AgentOnboardingState>(AGENT_ONBOARDING_KEY);

  const { ref } = useObserveResizeNode<HTMLDivElement>((node) => {
    heightRef.current = node.clientHeight;
    onChangeHeight(node.clientHeight);
  });

  const isDemoProject = project?.name === DEMO_PROJECT_NAME;
  const isOnboardingActive =
    !!onboardingState?.step &&
    onboardingState.step !== AGENT_ONBOARDING_STEPS.DONE;

  const hideBanner = !isDemoProject || !isOnboardingActive;

  useEffect(() => {
    onChangeHeight(!hideBanner ? heightRef.current : 0);
  }, [hideBanner, onChangeHeight]);

  if (hideBanner) {
    return null;
  }

  return (
    <div
      ref={ref}
      className="z-10 flex h-8 items-center gap-1.5 bg-slate-100 px-4"
    >
      <span className="comet-body-xs text-foreground">
        Connect your repo so Opik can help set up tracing, or instrument your
        code manually.
      </span>
      <Link
        to="/$workspaceName/get-started"
        params={{ workspaceName }}
        className="comet-body-xs inline-flex items-center gap-0.5 text-foreground underline underline-offset-2"
      >
        <PlugZap className="size-3" />
        Connect your agent
      </Link>
    </div>
  );
};

export default DemoProjectBanner;
