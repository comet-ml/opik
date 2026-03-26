import React from "react";
import { Navigate } from "@tanstack/react-router";
import AgentOnboardingOverlay from "./AgentOnboarding/AgentOnboardingOverlay";
import {
  AGENT_ONBOARDING_KEY,
  AGENT_ONBOARDING_STEPS,
} from "./AgentOnboarding/AgentOnboardingContext";
import useLocalStorageState from "use-local-storage-state";
import useAppStore from "@/store/AppStore";

const NewQuickstart: React.FunctionComponent = () => {
  const [agentOnboardingState] = useLocalStorageState<{
    step: unknown;
  }>(AGENT_ONBOARDING_KEY);

  const isOnboardingDone =
    agentOnboardingState?.step === AGENT_ONBOARDING_STEPS.DONE;

  if (!isOnboardingDone) {
    return <AgentOnboardingOverlay />;
  }

  return (
    <Navigate
      to="/$workspaceName/home"
      params={{ workspaceName: useAppStore.getState().activeWorkspaceName }}
    />
  );
};

export default NewQuickstart;
