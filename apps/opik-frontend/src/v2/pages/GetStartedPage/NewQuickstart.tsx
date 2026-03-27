import React from "react";
import { Navigate } from "@tanstack/react-router";
import AgentOnboardingOverlay from "./AgentOnboarding/AgentOnboardingOverlay";
import {
  AGENT_ONBOARDING_KEY,
  AGENT_ONBOARDING_STEPS,
  TRACES_OLDEST_FIRST_SORTING,
} from "./AgentOnboarding/AgentOnboardingContext";
import useLocalStorageState from "use-local-storage-state";
import useAppStore from "@/store/AppStore";
import useProjectByName from "@/api/projects/useProjectByName";
import { LOGS_TYPE } from "@/constants/traces";

const NewQuickstart: React.FunctionComponent = () => {
  const [agentOnboardingState] = useLocalStorageState<{
    step: unknown;
    agentName?: string;
    traceId?: string;
  }>(AGENT_ONBOARDING_KEY);

  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

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
    const traceId = agentOnboardingState?.traceId;

    return (
      <Navigate
        to="/$workspaceName/projects/$projectId/logs"
        params={{ workspaceName, projectId: project.id }}
        search={{
          logsType: LOGS_TYPE.traces,
          ...(traceId && {
            trace: traceId,
            traces_sorting: TRACES_OLDEST_FIRST_SORTING,
          }),
        }}
      />
    );
  }

  return <Navigate to="/$workspaceName/home" params={{ workspaceName }} />;
};

export default NewQuickstart;
