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
  detectedWorkspaceVersion: WorkspaceVersion | null;
  setDetectedWorkspaceVersion: (version: WorkspaceVersion | null) => void;
  previousWorkspaceName: string | null;
  setPreviousWorkspaceName: (workspaceName: string | null) => void;
  opikWorkspaceOverride: string | null;
  setOpikWorkspaceOverride: (workspaceName: string | null) => void;
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
  detectedWorkspaceVersion: null,
  setDetectedWorkspaceVersion: (version: WorkspaceVersion | null) =>
    set({ detectedWorkspaceVersion: version }),
  previousWorkspaceName: null,
  setPreviousWorkspaceName: (workspaceName: string | null) =>
    set({ previousWorkspaceName: workspaceName }),
  opikWorkspaceOverride: null,
  setOpikWorkspaceOverride: (workspaceName: string | null) =>
    set({ opikWorkspaceOverride: workspaceName }),
}));

export const useActiveWorkspaceName = () =>
  useAppStore((state) => state.activeWorkspaceName);

export const useActiveProjectId = () =>
  useAppStore((state) => state.activeProjectId);

export const useIsProjectLoading = () =>
  useAppStore((state) => state.isProjectLoading);

export const useWorkspaceVersion = () =>
  useAppStore((state) => state.workspaceVersion);

export const useDetectedWorkspaceVersion = () =>
  useAppStore((state) => state.detectedWorkspaceVersion);

export const usePreviousWorkspaceName = () =>
  useAppStore((state) => state.previousWorkspaceName);

export const useSetPreviousWorkspaceName = () =>
  useAppStore((state) => state.setPreviousWorkspaceName);

// The workspace that shared navigation (logo, breadcrumbs, account settings,
// invites, "Back to Opik") should target. Normally this is the active
// workspace, but special sections can run inside a workspace the user should
// not be sent "home" into — today the hidden AI-Spend workspace
// (`__ai_spend_<orgId>__`), where the active workspace is the spend workspace
// but those links must point back at the user's real workspace. That feature
// sets `opikWorkspaceOverride` to the real workspace while active and clears it
// on exit; generic components read `useOpikWorkspaceName()` and stay unaware of
// the feature. When no override is set it falls back to the active workspace.
export const useSetOpikWorkspaceOverride = () =>
  useAppStore((state) => state.setOpikWorkspaceOverride);

export const useOpikWorkspaceName = () =>
  useAppStore(
    (state) => state.opikWorkspaceOverride ?? state.activeWorkspaceName,
  );

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
