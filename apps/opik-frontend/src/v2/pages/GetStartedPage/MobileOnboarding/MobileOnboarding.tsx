import React, { useCallback, useEffect, useRef, useState } from "react";
import { Navigate, useNavigate } from "@tanstack/react-router";
import useLocalStorageState from "use-local-storage-state";
import { useActiveWorkspaceName, useUserEmail } from "@/store/AppStore";
import { trackEvent, OpikEvent } from "@/lib/analytics/tracking";
import MobileOnboardingShell from "./MobileOnboardingShell";
import WelcomeStep from "./WelcomeStep";
import TraceStep from "./TraceStep";
import IssuesStep from "./IssuesStep";
import ConnectStep from "./ConnectStep";
import { allIllustrationUrls } from "./illustrations";
import "./animations.css";

const MOBILE_ONBOARDING_KEY = "mobile-onboarding";
const TOTAL_STEPS = 4;

const STEP_CONFIG = [
  { nextLabel: "See Opik in action" },
  { nextLabel: "Let Opik recommend fixes" },
  { nextLabel: "Start sending traces" },
  { nextLabel: "Explore the platform", nextVariant: "outline" as const },
];

const MobileOnboarding: React.FC = () => {
  const workspaceName = useActiveWorkspaceName();
  const userEmail = useUserEmail();
  const navigate = useNavigate();
  const [step, setStep] = useState(1);

  const [completed, setCompleted] = useLocalStorageState<boolean>(
    `${MOBILE_ONBOARDING_KEY}-${workspaceName}`,
    { defaultValue: false },
  );

  const stepRef = useRef(1);
  const completedRef = useRef(completed);

  useEffect(() => {
    allIllustrationUrls.forEach((url) => {
      const img = new Image();
      img.src = url;
    });
  }, []);

  useEffect(() => {
    stepRef.current = step;
  }, [step]);

  useEffect(() => {
    return () => {
      if (completedRef.current) return;
      trackEvent(OpikEvent.MOBILE_ONBOARDING_ABANDONED, {
        step: stepRef.current,
      });
    };
  }, []);

  const handleNext = useCallback(() => {
    if (step < TOTAL_STEPS) {
      setStep(step + 1);
    } else {
      completedRef.current = true;
      setCompleted(true);
      void navigate({
        to: "/$workspaceName/home",
        params: { workspaceName },
      });
    }
  }, [step, navigate, workspaceName, setCompleted]);

  const handleBack = useCallback(() => {
    if (step > 1) {
      setStep(step - 1);
    }
  }, [step]);

  if (completed) {
    return <Navigate to="/$workspaceName/home" params={{ workspaceName }} />;
  }

  const config = STEP_CONFIG[step - 1];

  return (
    <MobileOnboardingShell
      step={step}
      totalSteps={TOTAL_STEPS}
      onBack={step > 1 ? handleBack : undefined}
      onNext={handleNext}
      nextLabel={config.nextLabel}
      nextVariant={config.nextVariant}
    >
      {step === 1 && <WelcomeStep />}
      {step === 2 && <TraceStep />}
      {step === 3 && <IssuesStep />}
      {step === 4 && <ConnectStep userEmail={userEmail} />}
    </MobileOnboardingShell>
  );
};

export default MobileOnboarding;
