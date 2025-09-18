import React from "react";
import OnboardingProvider, { useOnboarding } from "./OnboardingOverlayContext";
import { ONBOARDING_STEPS } from "./constants";
import { Role, AIJourney, StartPreference } from "./steps";
import imageOnboardingUrl from "/images/onboarding.png";

const STEPS = {
  [ONBOARDING_STEPS.ROLE]: Role,
  [ONBOARDING_STEPS.AI_JOURNEY]: AIJourney,
  [ONBOARDING_STEPS.START_PREFERENCE]: StartPreference,
} as const;

const STEP_IMAGES = {
  [ONBOARDING_STEPS.ROLE]: imageOnboardingUrl,
  [ONBOARDING_STEPS.AI_JOURNEY]: imageOnboardingUrl,
  [ONBOARDING_STEPS.START_PREFERENCE]: null,
} as const;

const OnboardingContent: React.FC = () => {
  const { currentStep } = useOnboarding();

  const RenderStep = STEPS[currentStep as keyof typeof STEPS];
  const stepImage = STEP_IMAGES[currentStep as keyof typeof STEP_IMAGES];

  return (
    <div className="absolute inset-0 z-50 overflow-auto bg-soft-background">
      <div className="mx-auto flex min-h-full max-w-[1360px] py-[60px] lg:px-10 lg:py-[120px]">
        <RenderStep />
        {stepImage && (
          <div className="hidden flex-1 items-center lg:block">
            <img
              src={stepImage}
              alt="Onboarding illustration"
              className="h-auto max-h-[600px] max-w-full object-contain"
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
