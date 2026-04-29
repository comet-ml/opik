import React from "react";
import { useNavigate } from "@tanstack/react-router";
import { useTheme } from "@/contexts/theme-provider";
import { THEME_MODE } from "@/constants/theme";
import { useActiveWorkspaceName } from "@/store/AppStore";
import useDemoProject from "@/api/projects/useDemoProject";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";
import {
  useAgentOnboarding,
  AGENT_ONBOARDING_STEPS,
} from "./AgentOnboardingContext";
import onboardingImageLightUrl from "/images/onboarding_image_light.svg";
import onboardingImageDarkUrl from "/images/onboarding_image_dark.svg";

const SelectIntentStep: React.FC = () => {
  const { goToStep } = useAgentOnboarding();
  const { themeMode } = useTheme();
  const workspaceName = useActiveWorkspaceName();
  const navigate = useNavigate();

  const { data: demoProject } = useDemoProject({ workspaceName, poll: false });

  const illustrationUrl =
    themeMode === THEME_MODE.DARK
      ? onboardingImageDarkUrl
      : onboardingImageLightUrl;

  const handlePickDemo = () => {
    trackEvent(OpikEvent.ONBOARDING_INTENT_SELECTED, { intent: "no-app" });
    goToStep(AGENT_ONBOARDING_STEPS.DEMO_LOADING, { agentName: "" });
    if (demoProject) {
      void navigate({
        to: "/$workspaceName/projects/$projectId/logs",
        params: { workspaceName, projectId: demoProject.id },
      });
    }
  };

  return (
    <div className="mx-auto flex min-h-full max-w-[1200px] items-center justify-center gap-14 px-10 py-16">
      <div className="w-full max-w-[480px]">
        <div className="flex flex-col gap-1.5">
          <p className="comet-body-s text-muted-slate">
            Welcome to Opik - let&apos;s get you set up
          </p>
          <h2 className="comet-title-m">Where are you right now?</h2>
        </div>

        <div className="flex flex-col gap-3 pt-6">
          <button
            onClick={() => {
              trackEvent(OpikEvent.ONBOARDING_INTENT_SELECTED, {
                intent: "has-app",
              });
              goToStep(AGENT_ONBOARDING_STEPS.AGENT_NAME, { agentName: "" });
            }}
            className="rounded-lg border border-border bg-background p-4 text-left transition-colors hover:border-primary hover:bg-muted"
          >
            <p className="comet-body-s font-medium">
              I have an AI agent or LLM app
            </p>
            <p className="comet-body-s mt-1 text-muted-slate">
              Connect it and track exactly what your agent does on every run.
            </p>
          </button>

          <button
            onClick={handlePickDemo}
            className="rounded-lg border border-border bg-background p-4 text-left transition-colors hover:border-primary hover:bg-muted"
          >
            <p className="comet-body-s font-medium">
              I don&apos;t have an AI agent yet
            </p>
          </button>
        </div>
      </div>

      <div className="hidden flex-1 items-center justify-center lg:flex">
        <img
          src={illustrationUrl}
          alt="Onboarding illustration"
          className="h-auto max-h-[400px] max-w-full object-contain"
        />
      </div>
    </div>
  );
};

export default SelectIntentStep;
