import React from "react";
import { Navigate } from "@tanstack/react-router";
import { useFeatureFlagVariantKey } from "posthog-js/react";
import useLocalStorageState from "use-local-storage-state";
import AgentOnboardingOverlay from "./AgentOnboarding/AgentOnboardingOverlay";
import AgentOnboardingShell from "./AgentOnboarding/AgentOnboardingShell";
import AgentNameStep from "./AgentOnboarding/AgentNameStep";
import AgentOnboardingProvider, {
  useAgentOnboarding,
  AGENT_ONBOARDING_KEY,
  AGENT_ONBOARDING_STEPS,
  AI_ASSISTED_OPIK_SKILLS_FEATURE_FLAG_KEY,
} from "./AgentOnboarding/AgentOnboardingContext";
import { useActiveWorkspaceName } from "@/store/AppStore";
import useProjectByName from "@/api/projects/useProjectByName";
import { IntegrationExplorer } from "@/v2/pages-shared/onboarding/IntegrationExplorer";
import OnboardingIntegrationsPage from "@/v2/pages-shared/onboarding/OnboardingIntegrationsPage/OnboardingIntegrationsPage";

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
        to="/$workspaceName/projects/$projectId/home"
        params={{ workspaceName, projectId: project.id }}
      />
    );
  }

  return <Navigate to="/$workspaceName/home" params={{ workspaceName }} />;
};

const ManualOnboardingSteps: React.FC = () => {
  const { currentStep } = useAgentOnboarding();

  if (currentStep === AGENT_ONBOARDING_STEPS.AGENT_NAME) {
    return (
      <AgentOnboardingShell>
        <AgentNameStep />
      </AgentOnboardingShell>
    );
  }

  return (
    <OnboardingIntegrationsPage
      IntegrationExplorer={IntegrationExplorer}
      source="get-started"
    />
  );
};

const ManualOnboardingQuickstart: React.FC = () => (
  <AgentOnboardingProvider>
    <ManualOnboardingSteps />
  </AgentOnboardingProvider>
);

const NewQuickstart: React.FC = () => {
  // Variants: "control" = agent onboarding modal with Opik skills tab; "connect-to-ollie" = agent onboarding modal with Connect to Ollie tab; "manual" = ask for project name, then render the full integrations page. Undefined (PostHog unavailable) falls back to "manual".
  const variant =
    useFeatureFlagVariantKey(AI_ASSISTED_OPIK_SKILLS_FEATURE_FLAG_KEY) ??
    "manual";

  if (variant === "manual") {
    return <ManualOnboardingQuickstart />;
  }

  return <AgentOnboardingQuickstart />;
};

export default NewQuickstart;
