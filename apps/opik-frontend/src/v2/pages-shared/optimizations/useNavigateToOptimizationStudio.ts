import { useCallback } from "react";
import { useNavigate } from "@tanstack/react-router";

import useAppStore, { useActiveProjectId } from "@/store/AppStore";

/**
 * Shared navigation into the Optimization Studio wizard (project-scoped
 * `/optimizations/new`), optionally pre-filling a demo template. Used by both
 * the optimizations empty state and the studio templates row so the routing
 * lives in a single place.
 */
const useNavigateToOptimizationStudio = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const activeProjectId = useActiveProjectId();
  const navigate = useNavigate();

  return useCallback(
    (templateId?: string) => {
      // The new-run wizard is now a sidebar over the runs list (no /new route):
      // open it via the `new` flag, optionally pre-filled from a demo template.
      navigate({
        to: "/$workspaceName/projects/$projectId/optimizations",
        params: { workspaceName, projectId: activeProjectId! },
        search: { new: true, ...(templateId ? { template: templateId } : {}) },
      });
    },
    [navigate, workspaceName, activeProjectId],
  );
};

export default useNavigateToOptimizationStudio;
