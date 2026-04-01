import { useMemo } from "react";
import { Project } from "@/types/projects";
import { PROJECT_ICON_COUNT } from "@/constants/projectIcons";

export default function useProjectIconIndices(projects: Project[] | undefined) {
  return useMemo(() => {
    if (!projects) return new Map<string, number>();

    const sorted = [...projects].sort(
      (a, b) =>
        new Date(a.created_at).getTime() - new Date(b.created_at).getTime(),
    );

    return new Map(sorted.map((p, i) => [p.id, i % PROJECT_ICON_COUNT]));
  }, [projects]);
}
