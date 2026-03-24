import { useEffect, useCallback } from "react";
import useLocalStorageState from "use-local-storage-state";
import useAppStore, { useActiveWorkspaceName } from "@/store/AppStore";
import useProjectsList from "@/api/projects/useProjectsList";

const ACTIVE_PROJECT_KEY_PREFIX = "opik-active-project-";
const LATEST_PROJECT_SORTING = [{ id: "last_updated_at", desc: true }];

/**
 * Syncs activeProjectId between localStorage and AppStore.
 * Mount once in the sidebar — all other components read from the store
 * via useActiveProjectId().
 */
export default function useActiveProject() {
  const workspaceName = useActiveWorkspaceName();

  const [storedProjectId, setStoredProjectId] = useLocalStorageState<
    string | null
  >(ACTIVE_PROJECT_KEY_PREFIX + workspaceName, {
    defaultValue: null,
  });

  const { data: latestProjectData } = useProjectsList(
    {
      workspaceName,
      page: 1,
      size: 1,
      sorting: LATEST_PROJECT_SORTING,
    },
    {
      enabled: !storedProjectId && !!workspaceName,
      staleTime: 30_000,
    },
  );

  const latestProjectId = latestProjectData?.content?.[0]?.id ?? null;
  const resolvedProjectId = storedProjectId ?? latestProjectId;

  useEffect(() => {
    if (useAppStore.getState().activeProjectId !== resolvedProjectId) {
      useAppStore.getState().setActiveProjectId(resolvedProjectId);
    }
  }, [resolvedProjectId]);

  const setActiveProjectId = useCallback(
    (projectId: string | null) => {
      setStoredProjectId(projectId);
      useAppStore.getState().setActiveProjectId(projectId);
    },
    [setStoredProjectId],
  );

  return { setActiveProjectId };
}
