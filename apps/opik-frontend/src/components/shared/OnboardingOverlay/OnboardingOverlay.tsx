import React from "react";
import OnboardingProvider, { useOnboarding } from "./OnboardingOverlayContext";
import { ONBOARDING_STEPS } from "./constants";
import Role from "./steps/Role";
import AIJourney from "./steps/AIJourney";
import StartPreference from "./steps/StartPreference";
import imageOnboardingUrl from "/images/onboarding.png";

const STEP_IMAGES = {
  [ONBOARDING_STEPS.ROLE]: imageOnboardingUrl,
  [ONBOARDING_STEPS.AI_JOURNEY]: imageOnboardingUrl,
  [ONBOARDING_STEPS.START_PREFERENCE]: null,
} as const;

const OnboardingContent: React.FC = () => {
  const { currentStep } = useOnboarding();

  const renderStep = () => {
    switch (currentStep) {
      case ONBOARDING_STEPS.ROLE:
        return <Role />;
      case ONBOARDING_STEPS.AI_JOURNEY:
        return <AIJourney />;
      case ONBOARDING_STEPS.START_PREFERENCE:
        return <StartPreference />;
      default:
        return null;
    }
  };
  const stepImage = STEP_IMAGES[currentStep as keyof typeof STEP_IMAGES];

  return (
    <div className="absolute inset-0 z-50 overflow-auto bg-soft-background">
      <div className="mx-auto flex min-h-full max-w-[1640px] py-[60px] lg:px-10 lg:pb-[40px] lg:pt-[120px]">
        {renderStep()}
        {stepImage && (
          <div className="hidden flex-1 items-center lg:block">
            <img
              src={stepImage}
              alt="Onboarding illustration"
              className="h-auto max-h-[calc(100vh-220px)] max-w-full object-contain"
            />
          </div>
        )}
      </div>
    </div>
  );
};

const OnboardingOverlay: React.FC = () => {
  return (
    <OnboardingProvider>
      <OnboardingContent />
    </OnboardingProvider>
  );
};

export default OnboardingOverlay;
