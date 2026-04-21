import { useEffect } from "react";
import useLocalStorageState from "use-local-storage-state";

import useProjectByName from "@/api/projects/useProjectByName";
import useTracesList from "@/api/traces/useTracesList";
import { useActiveWorkspaceName } from "@/store/AppStore";
import {
  AGENT_ONBOARDING_KEY,
  AGENT_ONBOARDING_STEPS,
  AgentOnboardingState,
} from "@/v2/pages/GetStartedPage/AgentOnboarding/AgentOnboardingContext";

interface UseAutoCompleteAgentOnboardingParams {
  agentName: string | undefined;
  enabled: boolean;
}

// Auto-complete onboarding when the user's agent project already has traces.
// Covers the case where a user connected their agent and logged traces in a
// previous session but never clicked "Explore Opik" to finalize the flow —
// without this, they'd keep seeing the demo-project banner forever.
// The `enabled` flag stops the effect from re-firing after DONE is written —
// the localStorage hook returns a new object reference on every render, so
// we can't rely on identity.
const useAutoCompleteAgentOnboarding = ({
  agentName,
  enabled,
}: UseAutoCompleteAgentOnboardingParams) => {
  const workspaceName = useActiveWorkspaceName();
  const [, setOnboardingState] = useLocalStorageState<AgentOnboardingState>(
    `${AGENT_ONBOARDING_KEY}-${workspaceName}`,
  );

  const { data: agentProject } = useProjectByName(
    { projectName: agentName ?? "" },
    { enabled: enabled && !!agentName },
  );
  const projectId = agentProject?.id;

  const { data: tracesData } = useTracesList(
    {
      projectId: projectId ?? "",
      page: 1,
      size: 1,
    },
    {
      enabled: enabled && !!projectId,
    },
  );
  const hasTraces = (tracesData?.total ?? 0) > 0;

  useEffect(() => {
    if (enabled && hasTraces) {
      setOnboardingState((prev) =>
        prev ? { ...prev, step: AGENT_ONBOARDING_STEPS.DONE } : prev,
      );
    }
  }, [enabled, hasTraces, setOnboardingState]);
};

export default useAutoCompleteAgentOnboarding;
