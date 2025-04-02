import { create } from "zustand";
import axiosInstance from "@/api/api";

type AppStore = {
  activeWorkspaceName: string;
  setActiveWorkspaceName: (workspaceName: string) => void;
};

const useAppStore = create<AppStore>((set) => ({
  activeWorkspaceName: "",
  setActiveWorkspaceName: (workspaceName) => {
    axiosInstance.defaults.headers.common["Comet-Workspace"] = workspaceName;
    set((state) => ({
      ...state,
      activeWorkspaceName: workspaceName,
    }));
  },
}));

export const useActiveWorkspaceName = () =>
  useAppStore((state) => state.activeWorkspaceName);

export default useAppStore;
