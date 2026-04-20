import React from "react";
import { Navigate } from "@tanstack/react-router";
import AgentOnboardingOverlay from "./AgentOnboarding/AgentOnboardingOverlay";
import {
  AGENT_ONBOARDING_KEY,
  AGENT_ONBOARDING_STEPS,
} from "./AgentOnboarding/AgentOnboardingContext";
import useLocalStorageState from "use-local-storage-state";
import useAppStore from "@/store/AppStore";
import { isDefaultUser } from "@/constants/user";
import useProjectByName from "@/api/projects/useProjectByName";
import { useUserScopedStorageKey } from "@/lib/userScopedStorageKey";

const NewQuickstart: React.FunctionComponent = () => {
  const storageKey = useUserScopedStorageKey(AGENT_ONBOARDING_KEY);
  const [agentOnboardingState] = useLocalStorageState<{
    step: unknown;
    agentName?: string;
  }>(storageKey);

  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const userName = useAppStore((state) => state.user.userName);

  const isOnboardingDone =
    agentOnboardingState?.step === AGENT_ONBOARDING_STEPS.DONE;

  const agentName = agentOnboardingState?.agentName || "";

  const { data: project, isPending } = useProjectByName(
    { projectName: agentName },
    { enabled: isOnboardingDone && !!agentName },
  );

  // Wait for WorkspacePreloader's setAppUser effect to resolve the real
  // userName. Rendering against the DEFAULT_USERNAME sentinel would flash
  // the onboarding overlay for a frame before the key flips to :<realUser>.
  if (isDefaultUser(userName)) {
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
