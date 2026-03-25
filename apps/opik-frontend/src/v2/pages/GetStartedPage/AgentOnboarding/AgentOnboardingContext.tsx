import React, {
  createContext,
  useContext,
  useCallback,
  useEffect,
} from "react";
import useLocalStorageState from "use-local-storage-state";
import posthog from "posthog-js";
import useSubmitOnboardingAnswerMutation from "@/api/feedback/useSubmitOnboardingAnswerMutation";

export const AGENT_ONBOARDING_KEY = "agent-onboarding";

export const AGENT_ONBOARDING_STEPS = {
  AGENT_NAME: "agent-name",
  CONNECT_AGENT: "connect-agent",
  DONE: "done",
} as const;

type AgentOnboardingStep =
  | null
  | (typeof AGENT_ONBOARDING_STEPS)[keyof typeof AGENT_ONBOARDING_STEPS];

interface AgentOnboardingState {
  step: AgentOnboardingStep;
  agentName: string;
}

interface AgentOnboardingContextValue {
  currentStep: AgentOnboardingStep;
  agentName: string;
  goToStep: (
    nextStep: AgentOnboardingStep,
    data: Omit<AgentOnboardingState, "step">,
  ) => void;
}

const AgentOnboardingContext =
  createContext<AgentOnboardingContextValue | null>(null);

export const useAgentOnboarding = () => {
  const context = useContext(AgentOnboardingContext);
  if (!context) {
    throw new Error(
      "useAgentOnboarding must be used within AgentOnboardingProvider",
    );
  }
  return context;
};

const DEFAULT_STATE: AgentOnboardingState = {
  step: AGENT_ONBOARDING_STEPS.AGENT_NAME,
  agentName: "",
};

interface AgentOnboardingProviderProps {
  children: React.ReactNode;
}

const AgentOnboardingProvider: React.FC<AgentOnboardingProviderProps> = ({
  children,
}) => {
  const [state, setState] = useLocalStorageState<AgentOnboardingState>(
    AGENT_ONBOARDING_KEY,
    { defaultValue: DEFAULT_STATE },
  );

  const submitAnswer = useSubmitOnboardingAnswerMutation();

  useEffect(() => {
    if (!state.step || state.step === AGENT_ONBOARDING_STEPS.DONE) return;

    const hash = `#${state.step}`;

    if (window.location.hash !== hash) {
      window.history.replaceState(null, "", hash);

      try {
        if (posthog.is_capturing()) {
          posthog.capture("$pageview");
        }
      } catch {
        // PostHog may not be initialized
      }
    }
  }, [state.step]);

  const goToStep = useCallback(
    (
      nextStep: AgentOnboardingStep,
      data: Omit<AgentOnboardingState, "step">,
    ) => {
      const stepKey =
        state.step && state.step !== AGENT_ONBOARDING_STEPS.DONE
          ? state.step
          : "unknown";

      submitAnswer.mutate({ answer: data.agentName, step: stepKey });

      setState({ ...data, step: nextStep });
    },
    [state.step, submitAnswer, setState],
  );

  if (state.step === AGENT_ONBOARDING_STEPS.DONE) {
    return null;
  }

  return (
    <AgentOnboardingContext.Provider
      value={{
        currentStep: state.step,
        agentName: state.agentName,
        goToStep,
      }}
    >
      {children}
    </AgentOnboardingContext.Provider>
  );
};

export default AgentOnboardingProvider;
