import { Navigate, useRouterState } from "@tanstack/react-router";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";

/**
 * Redirect from the legacy `/$datasetId/compare?optimizations=[id]` URL
 * to the new `/$optimizationId` URL.
 */
const OptimizationCompareRedirect = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const activeProjectId = useActiveProjectId();
  const searchStr = useRouterState({ select: (s) => s.location.searchStr });

  const searchParams = new URLSearchParams(searchStr);
  const optimizationsParam = searchParams.get("optimizations");

  let optimizationId: string | undefined;
  try {
    const ids: string[] = optimizationsParam
      ? JSON.parse(optimizationsParam)
      : [];
    optimizationId = ids?.[0];
  } catch {
    // invalid JSON, treat as no optimization ID
  }

  if (!optimizationId) {
    return (
      <Navigate
        to="/$workspaceName/projects/$projectId/optimizations"
        params={{ workspaceName, projectId: activeProjectId! }}
        replace
      />
    );
  }

  return (
    <Navigate
      to="/$workspaceName/projects/$projectId/optimizations/$optimizationId"
      params={{ workspaceName, projectId: activeProjectId!, optimizationId }}
      replace
    />
  );
};

export default OptimizationCompareRedirect;
