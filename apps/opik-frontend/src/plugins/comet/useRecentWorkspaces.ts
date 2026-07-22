import { useCallback } from "react";
import useLocalStorageState from "use-local-storage-state";

export type RecentWorkspacesMap = Record<string, number>;

interface UseRecentWorkspacesResult {
  visits: RecentWorkspacesMap;
  recordVisit: (workspaceName: string) => void;
  getVisitedAt: (workspaceName: string) => number;
}

// workspaceName is the globally-unique URL slug, so a single map keyed by it is
// safe across organizations. Mirrors the localStorage pattern in usePinnedProjects.
const STORAGE_KEY = "workspaces:recentlyVisited";
// Only the most-recent entries are ever displayed; cap the map so long-term use
// across many workspaces doesn't grow the localStorage entry unbounded.
const MAX_TRACKED = 50;

const useRecentWorkspaces = (): UseRecentWorkspacesResult => {
  const [visits = {}, setVisits] = useLocalStorageState<RecentWorkspacesMap>(
    STORAGE_KEY,
    { defaultValue: {} },
  );

  const recordVisit = useCallback(
    (workspaceName: string) => {
      if (!workspaceName) return;
      setVisits((prev = {}) => {
        const next = { ...prev, [workspaceName]: Date.now() };
        const entries = Object.entries(next);
        if (entries.length <= MAX_TRACKED) return next;
        return Object.fromEntries(
          entries.sort(([, a], [, b]) => b - a).slice(0, MAX_TRACKED),
        );
      });
    },
    [setVisits],
  );

  const getVisitedAt = useCallback(
    (workspaceName: string) => visits[workspaceName] ?? 0,
    [visits],
  );

  return { visits, recordVisit, getVisitedAt };
};

export default useRecentWorkspaces;
