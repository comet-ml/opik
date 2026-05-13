import React, { useEffect } from "react";
import { useAssistantCompute } from "@/plugins/comet/useAssistantBackend";
import useAssistantPhaseStore from "@/store/AssistantPhaseStore";

// Mounts at workspace level so PageLayout can read the resolved phase before
// the AssistantSidebar wrapper would otherwise reserve space for a disabled
// Ollie. Shares the `assistant-compute` query key with the sidebar, so this
// also serves as the compute prewarm.
const AssistantPrewarmer: React.FC = () => {
  const { data: computeResult } = useAssistantCompute();
  const setPhase = useAssistantPhaseStore((s) => s.setPhase);

  useEffect(() => {
    // Reset to "idle" while data is undefined (initial fetch, workspace
    // switch, refetch) so a previous workspace's "disabled" phase doesn't
    // keep the wrapper hidden in a workspace where it should be shown.
    if (!computeResult) {
      setPhase("idle");
      return;
    }
    setPhase(computeResult.enabled ? "idle" : "disabled");
  }, [computeResult, setPhase]);

  return null;
};

export default AssistantPrewarmer;
