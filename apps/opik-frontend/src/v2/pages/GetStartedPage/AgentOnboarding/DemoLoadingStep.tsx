import React from "react";
import {
  useAgentOnboarding,
  AGENT_ONBOARDING_STEPS,
} from "./AgentOnboardingContext";
import DemoLoadingContent from "./DemoLoadingContent";

const DemoLoadingStep: React.FC = () => {
  const { goToStep, agentName } = useAgentOnboarding();
  return (
    <DemoLoadingContent
      onRetry={() =>
        goToStep(AGENT_ONBOARDING_STEPS.SELECT_INTENT, { agentName })
      }
    />
  );
};

export default DemoLoadingStep;
