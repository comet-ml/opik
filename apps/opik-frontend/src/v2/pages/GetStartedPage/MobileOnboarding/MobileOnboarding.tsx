import React, { useCallback, useEffect, useRef, useState } from "react";
import { Navigate, useNavigate } from "@tanstack/react-router";
import useLocalStorageState from "use-local-storage-state";
import { useActiveWorkspaceName, useUserEmail } from "@/store/AppStore";
import { trackEvent, OpikEvent } from "@/lib/analytics/tracking";
import { useTheme } from "@/contexts/theme-provider";
import { MANUAL_ONBOARDING_KEY } from "../AgentOnboarding/AgentOnboardingContext";
import MobileOnboardingShell from "./MobileOnboardingShell";
import WelcomeStep from "./WelcomeStep";
import TraceStep from "./TraceStep";
import IssuesStep from "./IssuesStep";
import ConnectStep from "./ConnectStep";
import { illustrationUrlsByTheme } from "./illustrations";
import "./animations.css";

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
  const { themeMode } = useTheme();
  const [step, setStep] = useState(1);

  const [completed, setCompleted] = useLocalStorageState<boolean>(
    `${MANUAL_ONBOARDING_KEY}-${workspaceName}`,
    { defaultValue: false },
  );

  // ConnectStep form state lives here so it survives the panel remounts that
  // replay step entrance animations. Only what the user typed is stored
  // (null = untouched); the effective value falls back to the store's email,
  // which may resolve after mount.
  const [typedEmail, setTypedEmail] = useState<string | null>(null);
  const [connectEmailSent, setConnectEmailSent] = useState(false);
  const connectEmail = typedEmail ?? userEmail ?? "";

  const stepRef = useRef(1);
  const completedRef = useRef(completed);

  useEffect(() => {
    const images = illustrationUrlsByTheme[themeMode].map((url) => {
      const img = new Image();
      img.src = url;
      return img;
    });
    return () => {
      images.length = 0;
    };
  }, [themeMode]);

  useEffect(() => {
    let active = false;
    const id = setTimeout(() => {
      active = true;
    }, 0);

    const handleAbandon = () => {
      if (!active || completedRef.current) return;
      trackEvent(OpikEvent.MOBILE_ONBOARDING_ABANDONED, {
        step: stepRef.current,
      });
    };

    window.addEventListener("pagehide", handleAbandon);

    return () => {
      clearTimeout(id);
      window.removeEventListener("pagehide", handleAbandon);
      handleAbandon();
    };
  }, []);

  const handleNext = useCallback(() => {
    if (step < STEP_CONFIG.length) {
      const next = step + 1;
      stepRef.current = next;
      setStep(next);
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
      const prev = step - 1;
      stepRef.current = prev;
      setStep(prev);
    }
  }, [step]);

  // Swipe navigation: the shell reports the step the user scrolled to.
  const handleStepChange = useCallback((next: number) => {
    stepRef.current = next;
    setStep(next);
  }, []);

  if (completed) {
    return <Navigate to="/$workspaceName/home" params={{ workspaceName }} />;
  }

  const config = STEP_CONFIG[step - 1];

  return (
    <MobileOnboardingShell
      step={step}
      totalSteps={STEP_CONFIG.length}
      onBack={step > 1 ? handleBack : undefined}
      onNext={handleNext}
      onStepChange={handleStepChange}
      nextLabel={config.nextLabel}
      nextVariant={config.nextVariant}
    >
      <WelcomeStep onNext={handleNext} active={step === 1} />
      <TraceStep onNext={handleNext} />
      <IssuesStep />
      <ConnectStep
        email={connectEmail}
        onEmailChange={setTypedEmail}
        emailSent={connectEmailSent}
        onEmailSentChange={setConnectEmailSent}
      />
    </MobileOnboardingShell>
  );
};

export default MobileOnboarding;
