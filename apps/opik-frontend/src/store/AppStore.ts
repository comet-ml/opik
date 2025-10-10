import { create } from "zustand";
import axiosInstance from "@/api/api";
import { DEFAULT_USERNAME, isDefaultUser } from "@/constants/user";

type AppUser = {
  apiKey: string;
  userName: string;
};
type AppStore = {
  user: AppUser;
  setUser: (user: AppUser) => void;
  activeWorkspaceName: string;
  setActiveWorkspaceName: (workspaceName: string) => void;
};

const useAppStore = create<AppStore>((set) => ({
  user: {
    apiKey: "",
    userName: DEFAULT_USERNAME,
  },
  setUser: (user: AppUser) => set((state) => ({ ...state, user })),
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
export const useLoggedInUserName = () =>
  useAppStore((state) =>
    isDefaultUser(state.user.userName) ? undefined : state.user.userName,
  );
export const useUserApiKey = () => useAppStore((state) => state.user.apiKey);

export const useSetAppUser = () => useAppStore((state) => state.setUser);

export default useAppStore;
