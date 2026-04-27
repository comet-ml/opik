import React, { useCallback, useEffect, useRef, useState } from "react";
import { Navigate, useNavigate } from "@tanstack/react-router";
import { useFeatureFlagVariantKey } from "posthog-js/react";
import useLocalStorageState from "use-local-storage-state";
import AgentOnboardingOverlay from "./AgentOnboarding/AgentOnboardingOverlay";
import {
  AGENT_ONBOARDING_KEY,
  AGENT_ONBOARDING_STEPS,
  AI_ASSISTED_OPIK_SKILLS_FEATURE_FLAG_KEY,
  DEFAULT_ONBOARDING_FLOW,
  MANUAL_ONBOARDING_KEY,
} from "./AgentOnboarding/AgentOnboardingContext";
import { useActiveWorkspaceName } from "@/store/AppStore";
import useProjectByName from "@/api/projects/useProjectByName";
import { IntegrationExplorer } from "@/v2/pages-shared/onboarding/IntegrationExplorer";
import OnboardingIntegrationsPage from "@/shared/OnboardingIntegrationsPage/OnboardingIntegrationsPage";
import { usePermissions } from "@/contexts/PermissionsContext";
import DemoLoadingContent from "./AgentOnboarding/DemoLoadingContent";
import LoggedDataStatus from "@/v2/pages-shared/onboarding/IntegrationExplorer/components/LoggedDataStatus";
import useFirstTraceReceived from "@/api/projects/useFirstTraceReceived";

const AgentOnboardingQuickstart: React.FC = () => {
  const workspaceName = useActiveWorkspaceName();
  const [agentOnboardingState] = useLocalStorageState<{
    step: unknown;
    agentName?: string;
  }>(`${AGENT_ONBOARDING_KEY}-${workspaceName}`);

  const {
    permissions: { canCreateProjects },
  } = usePermissions();

  const isOnboardingDone =
    agentOnboardingState?.step === AGENT_ONBOARDING_STEPS.DONE;
  const agentName = agentOnboardingState?.agentName || "";

  const { data: project, isPending } = useProjectByName(
    { projectName: agentName },
    { enabled: isOnboardingDone && !!agentName },
  );

  if (!isOnboardingDone && canCreateProjects) {
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
  const [showDemoLoading, setShowDemoLoading] = useState(false);
  const workspaceName = useActiveWorkspaceName();
  const navigate = useNavigate();

  const [manualOnboardingDone, setManualOnboardingDone] =
    useLocalStorageState<boolean>(`${MANUAL_ONBOARDING_KEY}-${workspaceName}`, {
      defaultValue: false,
    });

  // Capture done state at mount — the re-entry guard should only redirect when
  // the user arrives already done, not when done flips mid-session (where
  // explicit navigation from handleExplore / DemoLoadingContent is in flight).
  const wasDoneOnMount = useRef(manualOnboardingDone);

  const isManualActive =
    variant === "manual" && !showDemoLoading && !manualOnboardingDone;
  const { hasTraces, firstTraceProjectId, pollExpired } = useFirstTraceReceived(
    {
      workspaceName,
      enabled: isManualActive,
      poll: isManualActive,
    },
  );

  useEffect(() => {
    if (variant === "manual" && !manualOnboardingDone) {
      window.history.replaceState(null, "", "#manual");
    }
  }, [variant, manualOnboardingDone]);

  const handleExplore = useCallback(() => {
    if (!firstTraceProjectId) return;
    setManualOnboardingDone(true);
    navigate({
      to: "/$workspaceName/projects/$projectId/logs",
      params: { workspaceName, projectId: firstTraceProjectId },
    });
  }, [navigate, workspaceName, firstTraceProjectId, setManualOnboardingDone]);

  if (variant === "manual") {
    if (wasDoneOnMount.current) {
      return <Navigate to="/$workspaceName/home" params={{ workspaceName }} />;
    }
    if (showDemoLoading) {
      return (
        <DemoLoadingContent
          onRetry={() => setShowDemoLoading(false)}
          retryLabel="Back to setup"
          onComplete={() => setManualOnboardingDone(true)}
        />
      );
    }
    return (
      <OnboardingIntegrationsPage
        IntegrationExplorer={IntegrationExplorer}
        source="get-started"
        banner={
          !pollExpired || hasTraces ? (
            <LoggedDataStatus
              status={hasTraces ? "logged" : "waiting"}
              onExplore={handleExplore}
            />
          ) : undefined
        }
        onSkip={() => setShowDemoLoading(true)}
      />
    );
  }

  return <AgentOnboardingQuickstart />;
};

export default NewQuickstart;
