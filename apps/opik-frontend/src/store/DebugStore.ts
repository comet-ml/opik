import { create } from "zustand";
import { devtools } from "zustand/middleware";

interface DebugStore {
  showAppDebugInfo: boolean;
  setShowAppDebugInfo: (show: boolean) => void;
}

export const useDebugStore = create<DebugStore>()(
  devtools((set) => ({
    showAppDebugInfo: false,
    setShowAppDebugInfo: (show) => set({ showAppDebugInfo: show }),
  })),
);
