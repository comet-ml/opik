import React from "react";
import Logo from "@/shared/Logo/Logo";
import AgentOnboardingProvider, {
  useAgentOnboarding,
  AGENT_ONBOARDING_STEPS,
} from "./AgentOnboardingContext";
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

const AgentOnboardingOverlay: React.FC = () => {
  return (
    <AgentOnboardingProvider>
      <div className="fixed inset-0 z-50 overflow-auto bg-soft-background">
        <div className="absolute left-[18px] top-[14.5px]">
          <Logo expanded />
        </div>
        <AgentOnboardingSteps />
      </div>
    </AgentOnboardingProvider>
  );
};

export default AgentOnboardingOverlay;
