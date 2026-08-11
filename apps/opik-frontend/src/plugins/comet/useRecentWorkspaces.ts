import { useCallback, useMemo } from "react";
import useLocalStorageState from "use-local-storage-state";
import { Workspace } from "@/plugins/comet/types";

// Enough to render a switcher row and navigate without a server read: the identity plus when it was
// last visited. The switcher no longer downloads the organization, so the recents section is built
// from this local record rather than filtered out of a full list.
export interface RecentWorkspace {
  workspaceId: string;
  workspaceName: string;
  organizationId: string;
  visitedAt: number;
}

type RecentWorkspaceIdentity = Pick<
  Workspace,
  "workspaceId" | "workspaceName" | "organizationId"
>;

// Keyed by workspaceName, which is the globally-unique URL slug, so one map is safe across
// organizations; organizationId rides along so the switcher can show only the current org's recents.
export type RecentWorkspacesMap = Record<string, RecentWorkspace>;

interface UseRecentWorkspacesResult {
  recentWorkspaces: RecentWorkspace[];
  recordVisit: (workspace: RecentWorkspaceIdentity) => void;
}

const STORAGE_KEY = "workspaces:recentlyVisited";
// Only the most-recent entries are ever displayed; cap the map so long-term use across many
// workspaces doesn't grow the localStorage entry unbounded.
const MAX_TRACKED = 50;

const useRecentWorkspaces = (): UseRecentWorkspacesResult => {
  const [visits = {}, setVisits] = useLocalStorageState<RecentWorkspacesMap>(
    STORAGE_KEY,
    { defaultValue: {} },
  );

  const recordVisit = useCallback(
    (workspace: RecentWorkspaceIdentity) => {
      if (!workspace?.workspaceName) return;
      setVisits((prev = {}) => {
        const next: RecentWorkspacesMap = {
          ...prev,
          [workspace.workspaceName]: {
            workspaceId: workspace.workspaceId,
            workspaceName: workspace.workspaceName,
            organizationId: workspace.organizationId,
            visitedAt: Date.now(),
          },
        };
        const entries = Object.values(next);
        if (entries.length <= MAX_TRACKED) return next;
        return Object.fromEntries(
          entries
            .sort((a, b) => b.visitedAt - a.visitedAt)
            .slice(0, MAX_TRACKED)
            .map((entry) => [entry.workspaceName, entry]),
        );
      });
    },
    [setVisits],
  );

  const recentWorkspaces = useMemo(
    () => Object.values(visits).sort((a, b) => b.visitedAt - a.visitedAt),
    [visits],
  );

  return { recentWorkspaces, recordVisit };
};

export default useRecentWorkspaces;
