import { create } from "zustand";
import axiosInstance from "@/api/api";

type ActiveUser = {
  apiKeys: string[];
  defaultWorkspace: string;
  userName: string;
};
type AppStore = {
  activeUser: ActiveUser | null;
  activeWorkspaceName: string;
  setActiveWorkspaceName: (workspaceName: string) => void;
  setActiveUser: (user: ActiveUser) => void;
};

const useAppStore = create<AppStore>((set) => ({
  activeUser: null,
  activeWorkspaceName: "",
  setActiveWorkspaceName: (workspaceName) => {
    axiosInstance.defaults.headers.common["Comet-Workspace"] = workspaceName;
    set((state) => ({
      ...state,
      activeWorkspaceName: workspaceName,
    }));
  },
  setActiveUser: (user: ActiveUser) =>
    set((state) => ({ ...state, activeUser: user })),
}));

export const useActiveWorkspaceName = () =>
  useAppStore((state) => state.activeWorkspaceName);
export const useActiveUserName = () =>
  useAppStore((state) => state.activeUser?.userName);
export const useActiveUserApiKey = () =>
  useAppStore((state) => state.activeUser?.apiKeys[0]);

export const useSetActiveUser = () =>
  useAppStore((state) => state.setActiveUser);

export default useAppStore;
