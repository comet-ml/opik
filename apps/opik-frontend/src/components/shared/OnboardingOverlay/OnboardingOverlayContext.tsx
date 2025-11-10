import React, { createContext, useContext, useEffect } from "react";
import useLocalStorageState from "use-local-storage-state";
import posthog from "posthog-js";
import { STEP_IDENTIFIERS } from "./constants";
import useSubmitOnboardingAnswerMutation from "@/api/feedback/useSubmitOnboardingAnswerMutation";

export const ONBOARDING_STEP_FINISHED = "done";
type OnboardingStep = null | 1 | 2 | 3 | typeof ONBOARDING_STEP_FINISHED;

interface OnboardingContextValue {
  currentStep: OnboardingStep;
  handleAnswer: (answer: string) => void;
  handleSkip: () => void;
  handleBack: () => void;
}

const OnboardingContext = createContext<OnboardingContextValue | null>(null);

export const useOnboarding = () => {
  const context = useContext(OnboardingContext);
  if (!context) {
    throw new Error("useOnboarding must be used within OnboardingProvider");
  }
  return context;
};

interface OnboardingProviderProps {
  children: React.ReactNode;
}

export const ONBOARDING_STEP_KEY = "onboarding-step";

export const OnboardingProvider: React.FunctionComponent<
  OnboardingProviderProps
> = ({ children }) => {
  const [currentStep, setStep] = useLocalStorageState<OnboardingStep>(
    ONBOARDING_STEP_KEY,
    {
      defaultValue: 1,
    },
  );

  const submitAnswer = useSubmitOnboardingAnswerMutation();

  // Update URL hash when step changes
  // This allows FullStory and PostHog to distinguish steps by URL
  useEffect(() => {
    if (!currentStep || currentStep === ONBOARDING_STEP_FINISHED) return;

    const stepKey = STEP_IDENTIFIERS[currentStep];
    const hash = `#${stepKey}`;

    // Update URL hash without triggering navigation
    if (window.location.hash !== hash) {
      window.history.replaceState(null, "", hash);

      // Manually trigger PostHog pageview for hash changes
      // replaceState doesn't trigger automatic pageview tracking
      try {
        if (posthog.is_capturing()) {
          posthog.capture("$pageview");
        }
      } catch (error) {
        // PostHog may not be initialized or available
        // Silently fail to not break the user experience
      }
    }
  }, [currentStep]);

  const handleAnswer = (answer: string) => {
    if (!currentStep || currentStep === ONBOARDING_STEP_FINISHED) return;

    const stepKey = STEP_IDENTIFIERS[currentStep];

    submitAnswer.mutate({ answer, step: stepKey });

    const nextStep =
      currentStep === 3
        ? ONBOARDING_STEP_FINISHED
        : ((currentStep + 1) as 1 | 2 | 3);
    setStep(nextStep);
  };

  const handleSkip = () => {
    if (!currentStep || currentStep === ONBOARDING_STEP_FINISHED) return;

    const stepKey = STEP_IDENTIFIERS[currentStep];

    submitAnswer.mutate({ answer: "Skipped", step: stepKey });

    const nextStep =
      currentStep === 3
        ? ONBOARDING_STEP_FINISHED
        : ((currentStep + 1) as 1 | 2 | 3);
    setStep(nextStep);
  };

  const handleBack = () => {
    if (
      !currentStep ||
      currentStep === 1 ||
      currentStep === ONBOARDING_STEP_FINISHED
    )
      return;

    setStep((currentStep - 1) as 1 | 2);
  };

  const contextValue: OnboardingContextValue = {
    currentStep,
    handleAnswer,
    handleSkip,
    handleBack,
  };

  if (currentStep === ONBOARDING_STEP_FINISHED) {
    return null;
  }

  return (
    <OnboardingContext.Provider value={contextValue}>
      {children}
    </OnboardingContext.Provider>
  );
};

export default OnboardingProvider;
