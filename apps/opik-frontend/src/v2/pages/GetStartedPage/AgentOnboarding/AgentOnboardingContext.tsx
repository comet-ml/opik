import React, {
  createContext,
  useContext,
  useCallback,
  useEffect,
  useState,
} from "react";
import useLocalStorageState from "use-local-storage-state";
import posthog from "posthog-js";
import useSubmitOnboardingAnswerMutation from "@/api/feedback/useSubmitOnboardingAnswerMutation";
import { useActiveWorkspaceName } from "@/store/AppStore";

export const AGENT_ONBOARDING_KEY = "agent-onboarding";
export const MANUAL_ONBOARDING_KEY = "manual-onboarding";

export const AI_ASSISTED_OPIK_SKILLS_FEATURE_FLAG_KEY =
  "onboarding-integrations-3-options";

export const DEFAULT_ONBOARDING_FLOW = "manual";

export const TRACES_OLDEST_FIRST_SORTING = [{ id: "id", desc: false }];

export const AGENT_ONBOARDING_STEPS = {
  SELECT_INTENT: "select-intent",
  AGENT_NAME: "agent-name",
  CONNECT_AGENT: "connect-agent",
  DEMO_LOADING: "demo-loading",
  DONE: "done",
} as const;

export type AgentOnboardingStep =
  | null
  | (typeof AGENT_ONBOARDING_STEPS)[keyof typeof AGENT_ONBOARDING_STEPS];

export interface AgentOnboardingState {
  step: AgentOnboardingStep;
  agentName: string;
  traceId?: string;
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
  step: AGENT_ONBOARDING_STEPS.SELECT_INTENT,
  agentName: "",
};

interface AgentOnboardingProviderProps {
  children: React.ReactNode;
}

const AgentOnboardingProvider: React.FC<AgentOnboardingProviderProps> = ({
  children,
}) => {
  const workspaceName = useActiveWorkspaceName();
  const [persistedState, setPersistedState] =
    useLocalStorageState<AgentOnboardingState>(
      `${AGENT_ONBOARDING_KEY}-${workspaceName}`,
      { defaultValue: DEFAULT_STATE },
    );
  const [transientStep, setTransientStep] =
    useState<AgentOnboardingStep | null>(null);

  const currentStep = transientStep ?? persistedState.step;

  const submitAnswer = useSubmitOnboardingAnswerMutation();

  useEffect(() => {
    if (
      !currentStep ||
      currentStep === AGENT_ONBOARDING_STEPS.DONE ||
      currentStep === AGENT_ONBOARDING_STEPS.DEMO_LOADING
    )
      return;

    const hash = `#${currentStep}`;
    const traceParam = new URLSearchParams(window.location.search).get("trace");

    if (window.location.hash !== hash && !traceParam) {
      window.history.replaceState(null, "", hash);

      try {
        if (posthog.is_capturing()) {
          posthog.capture("$pageview");
        }
      } catch {
        // PostHog may not be initialized
      }
    }
  }, [currentStep]);

  const goToStep = useCallback(
    (
      nextStep: AgentOnboardingStep,
      data: Omit<AgentOnboardingState, "step">,
    ) => {
      const stepKey =
        currentStep && currentStep !== AGENT_ONBOARDING_STEPS.DONE
          ? currentStep
          : "unknown";

      submitAnswer.mutate({ answer: data.agentName, step: stepKey });

      if (nextStep === AGENT_ONBOARDING_STEPS.DEMO_LOADING) {
        setTransientStep(nextStep);
        setPersistedState((prev) => ({ ...prev, ...data }));
      } else {
        setTransientStep(null);
        setPersistedState({ ...data, step: nextStep });
      }
    },
    [currentStep, submitAnswer, setPersistedState],
  );

  if (currentStep === AGENT_ONBOARDING_STEPS.DONE) {
    return null;
  }

  return (
    <AgentOnboardingContext.Provider
      value={{
        currentStep,
        agentName: persistedState.agentName,
        goToStep,
      }}
    >
      {children}
    </AgentOnboardingContext.Provider>
  );
};

export default AgentOnboardingProvider;
