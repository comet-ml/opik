import { create } from "zustand";
import axiosInstance from "@/api/api";
import { DEFAULT_USERNAME, isDefaultUser } from "@/constants/user";

type AppUser = {
  apiKey: string;
  userName: string;
};
type AppStore = {
  user: AppUser;
  activeWorkspaceName: string;
  setActiveWorkspaceName: (workspaceName: string) => void;
  setUser: (user: AppUser) => void;
};

const useAppStore = create<AppStore>((set) => ({
  user: {
    apiKey: "",
    userName: DEFAULT_USERNAME,
  },
  activeWorkspaceName: "",
  setActiveWorkspaceName: (workspaceName) => {
    axiosInstance.defaults.headers.common["Comet-Workspace"] = workspaceName;
    set((state) => ({
      ...state,
      activeWorkspaceName: workspaceName,
    }));
  },
  setUser: (user: AppUser) => set((state) => ({ ...state, user })),
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
