import React, { useState } from "react";
import { AxiosError, HttpStatusCode } from "axios";
import { Button } from "@/ui/button";
import { Input } from "@/ui/input";
import { Label } from "@/ui/label";
import useProjectCreateMutation from "@/api/projects/useProjectCreateMutation";
import {
  useAgentOnboarding,
  AGENT_ONBOARDING_STEPS,
} from "./AgentOnboardingContext";
import AgentOnboardingCard from "./AgentOnboardingCard";

const MIN_AGENT_NAME_LENGTH = 3;

const AgentNameStep: React.FC = () => {
  const { goToStep, agentName } = useAgentOnboarding();
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

  return (
    <form onSubmit={handleSubmit}>
      <AgentOnboardingCard
        title="Tell us about your agent"
        description="We'll use this to name your project and set up tracing automatically."
        footer={
          <>
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
          </>
        }
      >
        <div className="flex flex-col gap-2">
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
      </AgentOnboardingCard>
    </form>
  );
};

export default AgentNameStep;
