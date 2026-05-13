import { create } from "zustand";

import type { AssistantBackendPhase } from "@/types/assistant-sidebar";

interface AssistantPhaseStore {
  phase: AssistantBackendPhase;
  setPhase: (phase: AssistantBackendPhase) => void;
}

const useAssistantPhaseStore = create<AssistantPhaseStore>((set) => ({
  phase: "idle",
  setPhase: (phase) => set({ phase }),
}));

export default useAssistantPhaseStore;
