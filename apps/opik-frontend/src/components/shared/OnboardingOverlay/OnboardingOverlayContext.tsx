import React, { createContext, useContext } from "react";
import useLocalStorageState from "use-local-storage-state";
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
