import { Navigate, useRouterState } from "@tanstack/react-router";
import useAppStore from "@/store/AppStore";

/**
 * Redirect from the legacy `/$datasetId/compare?optimizations=[id]` URL
 * to the new `/$optimizationId` URL.
 */
const OptimizationCompareRedirect = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
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
        to="/$workspaceName/optimizations"
        params={{ workspaceName }}
        replace
      />
    );
  }

  return (
    <Navigate
      to="/$workspaceName/optimizations/$optimizationId"
      params={{ workspaceName, optimizationId }}
      replace
    />
  );
};

export default OptimizationCompareRedirect;
