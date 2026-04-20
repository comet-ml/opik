import React from "react";
import { Navigate } from "@tanstack/react-router";
import AgentOnboardingOverlay from "./AgentOnboarding/AgentOnboardingOverlay";
import {
  getAgentOnboardingKey,
  AGENT_ONBOARDING_STEPS,
} from "./AgentOnboarding/AgentOnboardingContext";
import useLocalStorageState from "use-local-storage-state";
import useAppStore from "@/store/AppStore";
import { isDefaultUser } from "@/constants/user";
import useProjectByName from "@/api/projects/useProjectByName";

const NewQuickstart: React.FunctionComponent = () => {
  const userName = useAppStore((s) => s.user.userName);
  const isUserResolved = !isDefaultUser(userName);
  const [agentOnboardingState] = useLocalStorageState<{
    step: unknown;
    agentName?: string;
  }>(getAgentOnboardingKey(userName));

  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const isOnboardingDone =
    agentOnboardingState?.step === AGENT_ONBOARDING_STEPS.DONE;

  const agentName = agentOnboardingState?.agentName || "";

  const { data: project, isPending } = useProjectByName(
    { projectName: agentName },
    { enabled: isUserResolved && isOnboardingDone && !!agentName },
  );

  if (!isUserResolved) {
    return null;
  }

  if (!isOnboardingDone) {
    return <AgentOnboardingOverlay />;
  }

  if (isPending && agentName) {
    return null;
  }

  if (project?.id) {
    return (
      <Navigate
        to="/$workspaceName/projects/$projectId/home"
        params={{ workspaceName, projectId: project.id }}
      />
    );
  }

  return <Navigate to="/$workspaceName/home" params={{ workspaceName }} />;
};

export default NewQuickstart;
