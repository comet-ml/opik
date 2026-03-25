import React from "react";
import AgentOnboardingProvider, {
  useAgentOnboarding,
  AGENT_ONBOARDING_STEPS,
} from "./AgentOnboardingContext";
import AgentNameStep from "./AgentNameStep";

const AgentOnboardingSteps: React.FC = () => {
  const { currentStep } = useAgentOnboarding();

  switch (currentStep) {
    case AGENT_ONBOARDING_STEPS.AGENT_NAME:
      return <AgentNameStep />;
    default:
      return null;
  }
};

const AgentOnboardingOverlay: React.FC = () => {
  return (
    <AgentOnboardingProvider>
      <AgentOnboardingSteps />
    </AgentOnboardingProvider>
  );
};

export default AgentOnboardingOverlay;
