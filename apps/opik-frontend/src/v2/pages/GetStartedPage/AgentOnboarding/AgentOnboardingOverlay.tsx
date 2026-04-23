import React from "react";
import AgentOnboardingProvider, {
  useAgentOnboarding,
  AGENT_ONBOARDING_STEPS,
} from "./AgentOnboardingContext";
import AgentOnboardingShell from "./AgentOnboardingShell";
import SelectIntentStep from "./SelectIntentStep";
import AgentNameStep from "./AgentNameStep";
import ConnectAgentStep from "./ConnectAgentStep";
import DemoLoadingStep from "./DemoLoadingStep";

const AgentOnboardingSteps: React.FC = () => {
  const { currentStep } = useAgentOnboarding();

  switch (currentStep) {
    case AGENT_ONBOARDING_STEPS.SELECT_INTENT:
      return <SelectIntentStep />;
    case AGENT_ONBOARDING_STEPS.AGENT_NAME:
      return <AgentNameStep />;
    case AGENT_ONBOARDING_STEPS.CONNECT_AGENT:
      return <ConnectAgentStep />;
    case AGENT_ONBOARDING_STEPS.DEMO_LOADING:
      return <DemoLoadingStep />;
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
