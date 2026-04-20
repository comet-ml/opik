import { useEffect } from "react";
import useLocalStorageState from "use-local-storage-state";

import useTracesList from "@/api/traces/useTracesList";
import { useActiveWorkspaceName } from "@/store/AppStore";
import {
  AGENT_ONBOARDING_KEY,
  AGENT_ONBOARDING_STEPS,
  AgentOnboardingState,
} from "@/v2/pages/GetStartedPage/AgentOnboarding/AgentOnboardingContext";

interface UseAutoCompleteAgentOnboardingParams {
  activeProjectId: string | null;
  enabled: boolean;
}

// Auto-complete onboarding when the user lands on their own agent project that
// already has traces. This covers the case where a user connected their agent
// and logged traces in a previous session but never clicked "Explore Opik" to
// finalize the flow — without this, they'd keep seeing the banner forever.
// The `enabled` flag (caller's isOnboardingProject && isOnboardingActive) stops
// the effect from re-firing after DONE is written — the localStorage hook
// returns a new object reference on every render, so we can't rely on identity.
const useAutoCompleteAgentOnboarding = ({
  activeProjectId,
  enabled,
}: UseAutoCompleteAgentOnboardingParams) => {
  const workspaceName = useActiveWorkspaceName();
  const [, setOnboardingState] = useLocalStorageState<AgentOnboardingState>(
    `${AGENT_ONBOARDING_KEY}-${workspaceName}`,
  );

  const { data: tracesData } = useTracesList(
    {
      projectId: activeProjectId ?? "",
      page: 1,
      size: 1,
    },
    {
      enabled: !!activeProjectId && enabled,
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
