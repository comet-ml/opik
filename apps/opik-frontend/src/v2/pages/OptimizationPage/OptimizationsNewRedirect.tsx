import { Navigate } from "@tanstack/react-router";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";

/**
 * Redirect the legacy full-page `/optimizations/new` URL to the runs list with
 * the new-run sidebar open (`?new=true`). The new-run flow moved from a page
 * into a sidebar, so old deep links / bookmarks would otherwise be consumed by
 * the `/$optimizationId` detail route (as id "new") and 404.
 */
const OptimizationsNewRedirect = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const activeProjectId = useActiveProjectId();

  return (
    <Navigate
      to="/$workspaceName/projects/$projectId/optimizations"
      params={{ workspaceName, projectId: activeProjectId! }}
      search={{ new: "true" }}
      replace
    />
  );
};

export default OptimizationsNewRedirect;
