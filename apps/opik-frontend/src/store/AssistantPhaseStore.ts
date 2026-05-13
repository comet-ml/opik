import { create } from "zustand";

import type { AssistantBackendPhase } from "@/types/assistant-sidebar";

interface AssistantPhaseStore {
  phase: AssistantBackendPhase;
  setPhase: (phase: AssistantBackendPhase) => void;
}

// Default "idle" keeps OSS behavior unchanged: when no plugin writes to this
// store, the layout gate `phase !== "disabled"` stays true and falls through
// to the existing `!!AssistantSidebar` check, which is null in OSS.
const useAssistantPhaseStore = create<AssistantPhaseStore>((set) => ({
  phase: "idle",
  setPhase: (phase) => set({ phase }),
}));

export default useAssistantPhaseStore;
