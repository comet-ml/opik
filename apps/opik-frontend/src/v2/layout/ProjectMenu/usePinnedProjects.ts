import { useCallback } from "react";
import useLocalStorageState from "use-local-storage-state";
import { Project } from "@/types/projects";

export type PinnedProject = Pick<Project, "id" | "name">;

interface UsePinnedProjectsResult {
  pinnedProjects: PinnedProject[];
  isPinned: (projectId: string) => boolean;
  pinProject: (project: PinnedProject) => void;
  unpinProject: (projectId: string) => void;
}

const usePinnedProjects = (workspaceName: string): UsePinnedProjectsResult => {
  const [pinnedProjects = [], setPinnedProjects] = useLocalStorageState<
    PinnedProject[]
  >(`projects:pinnedConfig:${workspaceName}`, { defaultValue: [] });

  const isPinned = useCallback(
    (projectId: string) => pinnedProjects.some((p) => p.id === projectId),
    [pinnedProjects],
  );

  const pinProject = useCallback(
    (project: PinnedProject) => {
      setPinnedProjects((prev = []) =>
        prev.some((p) => p.id === project.id)
          ? prev
          : [...prev, { id: project.id, name: project.name }],
      );
    },
    [setPinnedProjects],
  );

  const unpinProject = useCallback(
    (projectId: string) => {
      setPinnedProjects((prev = []) => prev.filter((p) => p.id !== projectId));
    },
    [setPinnedProjects],
  );

  return { pinnedProjects, isPinned, pinProject, unpinProject };
};

export default usePinnedProjects;
