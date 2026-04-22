import React, { useEffect } from "react";
import { useNavigate } from "@tanstack/react-router";
import { useActiveWorkspaceName } from "@/store/AppStore";
import useDemoProject from "@/api/projects/useDemoProject";
import useProgressSimulation from "@/hooks/useProgressSimulation";
import OwlArt from "@/shared/OwlArt";
import { Button } from "@/ui/button";
import {
  useAgentOnboarding,
  AGENT_ONBOARDING_STEPS,
} from "./AgentOnboardingContext";

const LOADING_LABELS = [
  "Creating demo project…",
  "Setting up sample traces…",
  "Preparing some data for you…",
  "Almost ready…",
];

const DemoLoadingStep: React.FC = () => {
  const workspaceName = useActiveWorkspaceName();
  const navigate = useNavigate();
  const { goToStep, agentName } = useAgentOnboarding();

  const {
    data: demoProject,
    isLoading,
    pollExpired,
  } = useDemoProject(
    { workspaceName, poll: true },
    { refetchOnMount: "always" },
  );

  const { message } = useProgressSimulation({
    messages: LOADING_LABELS,
    isPending: !pollExpired,
    loop: true,
  });

  useEffect(() => {
    if (demoProject) {
      void navigate({
        to: "/$workspaceName/projects/$projectId/logs",
        params: { workspaceName, projectId: demoProject.id },
      });
    }
  }, [demoProject, navigate, workspaceName]);

  if (isLoading) {
    return null;
  }

  if (pollExpired && !demoProject) {
    return (
      <div className="flex min-h-full items-center justify-center">
        <div className="flex flex-col items-center gap-4">
          <OwlArt className="size-[72px]" />
          <p className="comet-body-s text-center text-muted-slate">
            Demo data is taking longer than expected.
          </p>
          <Button
            variant="outline"
            size="sm"
            onClick={() =>
              goToStep(AGENT_ONBOARDING_STEPS.SELECT_INTENT, { agentName })
            }
          >
            Try again
          </Button>
        </div>
      </div>
    );
  }

  return (
    <div className="flex min-h-full items-center justify-center">
      <div className="flex flex-col items-center gap-4">
        <OwlArt className="size-[72px]" />
        <p className="font-code text-sm text-muted-slate">
          {message || LOADING_LABELS[0]}
        </p>
      </div>
    </div>
  );
};

export default DemoLoadingStep;
