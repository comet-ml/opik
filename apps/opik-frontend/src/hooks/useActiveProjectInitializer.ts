import { useEffect, useMemo } from "react";
import useAppStore, { useActiveWorkspaceName } from "@/store/AppStore";
import useProjectsList from "@/api/projects/useProjectsList";

const KEY_PREFIX = "opik-active-project-";
const LATEST_PROJECT_SORTING = [{ id: "last_updated_at", desc: true }];

function getStoredActiveProject(workspaceName: string): string | null {
  const key = KEY_PREFIX + workspaceName;
  try {
    const raw = localStorage.getItem(key);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

export function setActiveProject(
  workspaceName: string,
  projectId: string | null,
) {
  const key = KEY_PREFIX + workspaceName;
  if (projectId) {
    localStorage.setItem(key, JSON.stringify(projectId));
  } else {
    localStorage.removeItem(key);
  }
  if (useAppStore.getState().activeProjectId !== projectId) {
    useAppStore.getState().setActiveProjectId(projectId);
  }
}

export function useActiveProjectInitializer() {
  const workspaceName = useActiveWorkspaceName();
  const storedId = useMemo(
    () => getStoredActiveProject(workspaceName),
    [workspaceName],
  );

  const { data, isPending } = useProjectsList(
    {
      workspaceName,
      page: 1,
      size: 1,
      sorting: LATEST_PROJECT_SORTING,
    },
    {
      enabled: !storedId && !!workspaceName,
      staleTime: 30_000,
    },
  );

  const latestId = data?.content?.[0]?.id ?? null;
  const resolvedId = storedId ?? latestId;
  const isLoading = !storedId && isPending;

  useEffect(() => {
    if (useAppStore.getState().activeProjectId !== resolvedId) {
      setActiveProject(workspaceName, resolvedId);
    }
  }, [resolvedId, workspaceName]);

  useEffect(() => {
    if (useAppStore.getState().isProjectLoading !== isLoading) {
      useAppStore.getState().setIsProjectLoading(isLoading);
    }
  }, [isLoading]);
}
