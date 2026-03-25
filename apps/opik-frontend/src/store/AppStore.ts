import { create } from "zustand";
import axiosInstance from "@/api/api";
import { DEFAULT_USERNAME, isDefaultUser } from "@/constants/user";

export type WorkspaceVersion = "v1" | "v2";

type AppUser = {
  apiKey: string;
  userName: string;
};
type AppStore = {
  user: AppUser;
  setUser: (user: AppUser) => void;
  activeWorkspaceName: string;
  setActiveWorkspaceName: (workspaceName: string) => void;
  activeProjectId: string | null;
  setActiveProjectId: (projectId: string | null) => void;
  isProjectLoading: boolean;
  setIsProjectLoading: (loading: boolean) => void;
  workspaceVersion: WorkspaceVersion | null;
  setWorkspaceVersion: (version: WorkspaceVersion) => void;
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
  activeProjectId: null,
  setActiveProjectId: (projectId: string | null) =>
    set({ activeProjectId: projectId }),
  isProjectLoading: true,
  setIsProjectLoading: (loading: boolean) => set({ isProjectLoading: loading }),
  workspaceVersion: null,
  setWorkspaceVersion: (version: WorkspaceVersion) =>
    set({ workspaceVersion: version }),
}));

export const useActiveWorkspaceName = () =>
  useAppStore((state) => state.activeWorkspaceName);

export const useActiveProjectId = () =>
  useAppStore((state) => state.activeProjectId);

export const useIsProjectLoading = () =>
  useAppStore((state) => state.isProjectLoading);

export const useWorkspaceVersion = () =>
  useAppStore((state) => state.workspaceVersion);

const getResolvedUserName = (userName: string, defaultUserName?: string) => {
  return isDefaultUser(userName) ? defaultUserName : userName;
};

export const useLoggedInUserName = () =>
  useAppStore((state) => getResolvedUserName(state.user.userName));
// If the user is not defined, use "admin" as the user name
// it happens for deployment without comet plugin, and BE automatically
// sets the user name to "admin", we need to mimic this behavior in FE
// to have correcly working feedback scores and comments
export const useLoggedInUserNameOrOpenSourceDefaultUser = () =>
  useAppStore(
    (state) => getResolvedUserName(state.user.userName, "admin") as string,
  );

export const useUserApiKey = () => useAppStore((state) => state.user.apiKey);

export const useSetAppUser = () => useAppStore((state) => state.setUser);

export default useAppStore;
