import React from "react";
import AgentOnboardingProvider, {
  useAgentOnboarding,
  AGENT_ONBOARDING_STEPS,
} from "./AgentOnboardingContext";
import AgentOnboardingShell from "./AgentOnboardingShell";
import AgentNameStep from "./AgentNameStep";
import ConnectAgentStep from "./ConnectAgentStep";

const AgentOnboardingSteps: React.FC = () => {
  const { currentStep } = useAgentOnboarding();

  switch (currentStep) {
    case AGENT_ONBOARDING_STEPS.AGENT_NAME:
      return <AgentNameStep />;
    case AGENT_ONBOARDING_STEPS.CONNECT_AGENT:
      return <ConnectAgentStep />;
    default:
      return null;
  }
};

const AgentOnboardingOverlay: React.FC = () => (
  <AgentOnboardingProvider>
    <AgentOnboardingShell>
      <AgentOnboardingSteps />
    </AgentOnboardingShell>
  </AgentOnboardingProvider>
);

export default AgentOnboardingOverlay;
