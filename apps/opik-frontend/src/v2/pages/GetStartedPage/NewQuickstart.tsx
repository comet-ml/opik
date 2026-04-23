import React from "react";
import { Navigate } from "@tanstack/react-router";
import { useFeatureFlagVariantKey } from "posthog-js/react";
import useLocalStorageState from "use-local-storage-state";
import AgentOnboardingOverlay from "./AgentOnboarding/AgentOnboardingOverlay";
import {
  AGENT_ONBOARDING_KEY,
  AGENT_ONBOARDING_STEPS,
  AI_ASSISTED_OPIK_SKILLS_FEATURE_FLAG_KEY,
  DEFAULT_ONBOARDING_FLOW,
} from "./AgentOnboarding/AgentOnboardingContext";
import { useActiveWorkspaceName } from "@/store/AppStore";
import useProjectByName from "@/api/projects/useProjectByName";
import { IntegrationExplorer } from "@/v2/pages-shared/onboarding/IntegrationExplorer";
import OnboardingIntegrationsPage from "@/shared/OnboardingIntegrationsPage/OnboardingIntegrationsPage";

const AgentOnboardingQuickstart: React.FC = () => {
  const workspaceName = useActiveWorkspaceName();
  const [agentOnboardingState] = useLocalStorageState<{
    step: unknown;
    agentName?: string;
  }>(`${AGENT_ONBOARDING_KEY}-${workspaceName}`);

  const isOnboardingDone =
    agentOnboardingState?.step === AGENT_ONBOARDING_STEPS.DONE;
  const agentName = agentOnboardingState?.agentName || "";

  const { data: project, isPending } = useProjectByName(
    { projectName: agentName },
    { enabled: isOnboardingDone && !!agentName },
  );

  if (!isOnboardingDone) {
    return <AgentOnboardingOverlay />;
  }

  if (isPending && agentName) {
    return null;
  }

  if (project?.id) {
    return (
      <Navigate
        to="/$workspaceName/projects/$projectId/logs"
        params={{ workspaceName, projectId: project.id }}
      />
    );
  }

  return <Navigate to="/$workspaceName/home" params={{ workspaceName }} />;
};

const NewQuickstart: React.FC = () => {
  // Variants: "control" = agent onboarding modal with Opik skills tab; "connect-to-ollie" = agent onboarding modal with Connect to Ollie tab; "manual" = skip the modal and render the full integrations page. Undefined (PostHog unavailable) falls back to "control".
  const variant =
    useFeatureFlagVariantKey(AI_ASSISTED_OPIK_SKILLS_FEATURE_FLAG_KEY) ??
    DEFAULT_ONBOARDING_FLOW;

  if (variant === "manual") {
    return (
      <OnboardingIntegrationsPage
        IntegrationExplorer={IntegrationExplorer}
        source="get-started"
      />
    );
  }

  return <AgentOnboardingQuickstart />;
};

export default NewQuickstart;
