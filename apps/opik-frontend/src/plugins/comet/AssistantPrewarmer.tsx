import React, { useEffect } from "react";
import useAssistantBackend from "@/plugins/comet/useAssistantBackend";
import useAssistantPhaseStore from "@/store/AssistantPhaseStore";

// Mounts at workspace level so PageLayout can read the resolved phase before
// the AssistantSidebar wrapper would otherwise reserve space for a disabled
// Ollie. Shares the `assistant-compute` query key with the sidebar, so this
// also serves as the compute prewarm.
const AssistantPrewarmer: React.FC = () => {
  const { phase } = useAssistantBackend();
  const setPhase = useAssistantPhaseStore((s) => s.setPhase);

  useEffect(() => {
    setPhase(phase);
  }, [phase, setPhase]);

  return null;
};

export default AssistantPrewarmer;
