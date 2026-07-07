import { Navigate } from "@tanstack/react-router";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";

// Redirect the legacy `/optimizations/new` URL to the runs list with the
// sidebar open. Without it, old links hit the `/$optimizationId` detail route
// (as id "new") and 404.
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
