import React, { useState } from "react";
import { Button } from "@/ui/button";
import { Input } from "@/ui/input";
import { Label } from "@/ui/label";
import {
  useAgentOnboarding,
  AGENT_ONBOARDING_STEPS,
} from "./AgentOnboardingContext";
import AgentOnboardingCard from "./AgentOnboardingCard";

const MIN_AGENT_NAME_LENGTH = 3;

const AgentNameStep: React.FC = () => {
  const { goToStep, agentName } = useAgentOnboarding();
  const [name, setName] = useState(agentName);

  const trimmedName = name.trim();
  const isValid = trimmedName.length >= MIN_AGENT_NAME_LENGTH;

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!isValid) return;
    goToStep(AGENT_ONBOARDING_STEPS.CONNECT_AGENT, {
      agentName: trimmedName,
    });
  };

  return (
    <form onSubmit={handleSubmit}>
      <AgentOnboardingCard
        title="Tell us about your agent"
        description="We'll use this to name your project and set up tracing automatically."
        footer={
          <Button
            type="submit"
            disabled={!isValid}
            id="onboarding-agent-name-continue"
            data-fs-element="onboarding-agent-name-continue"
          >
            Continue
          </Button>
        }
      >
        <div className="flex flex-col gap-2">
          <Label htmlFor="agent-name">Agent name</Label>
          <Input
            id="agent-name"
            placeholder="e.g. RAG Pipeline, Customer Support Bot, Code Review Agent..."
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </div>
      </AgentOnboardingCard>
    </form>
  );
};

export default AgentNameStep;
