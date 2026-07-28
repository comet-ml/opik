import { useCallback } from "react";
import useLocalStorageState from "use-local-storage-state";
import { Workspace } from "@/plugins/comet/types";

export type PinnedWorkspace = Pick<Workspace, "workspaceId" | "workspaceName">;

interface UsePinnedWorkspacesResult {
  pinnedWorkspaces: PinnedWorkspace[];
  isPinned: (workspaceId: string) => boolean;
  pinWorkspace: (workspace: PinnedWorkspace) => void;
  unpinWorkspace: (workspaceId: string) => void;
}

// Scoped per organization: pinning is only meaningful among the workspaces of the
// current org. Mirrors usePinnedProjects (localStorage, {id,name}, functional updates).
const usePinnedWorkspaces = (
  organizationId: string | undefined,
): UsePinnedWorkspacesResult => {
  const [pinnedWorkspaces = [], setPinnedWorkspaces] = useLocalStorageState<
    PinnedWorkspace[]
  >(`workspaces:pinnedConfig:${organizationId ?? "unknown"}`, {
    defaultValue: [],
  });

  const isPinned = useCallback(
    (workspaceId: string) =>
      pinnedWorkspaces.some((w) => w.workspaceId === workspaceId),
    [pinnedWorkspaces],
  );

  const pinWorkspace = useCallback(
    (workspace: PinnedWorkspace) => {
      setPinnedWorkspaces((prev = []) =>
        prev.some((w) => w.workspaceId === workspace.workspaceId)
          ? prev
          : [
              ...prev,
              {
                workspaceId: workspace.workspaceId,
                workspaceName: workspace.workspaceName,
              },
            ],
      );
    },
    [setPinnedWorkspaces],
  );

  const unpinWorkspace = useCallback(
    (workspaceId: string) => {
      setPinnedWorkspaces((prev = []) =>
        prev.filter((w) => w.workspaceId !== workspaceId),
      );
    },
    [setPinnedWorkspaces],
  );

  return { pinnedWorkspaces, isPinned, pinWorkspace, unpinWorkspace };
};

export default usePinnedWorkspaces;
