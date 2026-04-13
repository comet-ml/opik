import React, { useState } from "react";
import { AxiosError, HttpStatusCode } from "axios";
import { Button } from "@/ui/button";
import { Input } from "@/ui/input";
import { Label } from "@/ui/label";
import { useTheme } from "@/contexts/theme-provider";
import { THEME_MODE } from "@/constants/theme";
import useProjectCreateMutation from "@/api/projects/useProjectCreateMutation";
import {
  useAgentOnboarding,
  AGENT_ONBOARDING_STEPS,
} from "./AgentOnboardingContext";
import onboardingImageLightUrl from "/images/onboarding_image_light.svg";
import onboardingImageDarkUrl from "/images/onboarding_image_dark.svg";

const MIN_AGENT_NAME_LENGTH = 3;

const AgentNameStep: React.FC = () => {
  const { goToStep, agentName } = useAgentOnboarding();
  const { themeMode } = useTheme();
  const [name, setName] = useState(agentName);
  const [error, setError] = useState("");

  const { mutateAsync: createProject, isPending } = useProjectCreateMutation({
    showErrorToast: false,
  });

  const trimmedName = name.trim();
  const isValid = trimmedName.length >= MIN_AGENT_NAME_LENGTH;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!isValid || isPending || error) return;

    try {
      await createProject({ project: { name: trimmedName } });

      goToStep(AGENT_ONBOARDING_STEPS.CONNECT_AGENT, {
        agentName: trimmedName,
      });
    } catch (err) {
      const axiosError = err as AxiosError;
      if (axiosError.response?.status === HttpStatusCode.Conflict) {
        setError("A project with this name already exists");
      } else {
        setError("Failed to create project. Please try again.");
      }
    }
  };

  const illustrationUrl =
    themeMode === THEME_MODE.DARK
      ? onboardingImageDarkUrl
      : onboardingImageLightUrl;

  return (
    <div className="mx-auto flex min-h-full max-w-[1200px] items-center justify-center gap-14 px-10 py-16">
      <form onSubmit={handleSubmit} className="w-full max-w-[480px]">
        <div className="flex flex-col gap-1.5">
          <h2 className="comet-title-m">Tell us about your agent</h2>
          <p className="comet-body-s text-muted-slate">
            We&apos;ll use this to name your project and set up tracing
            automatically.
          </p>
        </div>

        <div className="flex flex-col gap-2 pt-4">
          <Label htmlFor="agent-name">Agent name</Label>
          <Input
            id="agent-name"
            dimension="sm"
            placeholder="e.g. RAG Pipeline, Customer Support Bot, Code Review Agent..."
            value={name}
            onChange={(e) => {
              setName(e.target.value);
              setError("");
            }}
          />
        </div>

        <div className="flex items-center gap-2 pt-3">
          {error && (
            <p className="comet-body-xs mr-auto text-destructive">{error}</p>
          )}
          <Button
            type="submit"
            size="sm"
            disabled={!isValid || isPending || !!error}
            id="onboarding-agent-name-continue"
            data-fs-element="onboarding-agent-name-continue"
          >
            {isPending ? "Creating" : "Continue"}
          </Button>
        </div>
      </form>

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

export default AgentNameStep;
