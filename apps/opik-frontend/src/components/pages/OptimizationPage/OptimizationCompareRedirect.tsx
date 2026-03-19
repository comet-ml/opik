import { Navigate } from "@tanstack/react-router";
import { JsonParam, useQueryParam } from "use-query-params";
import useAppStore from "@/store/AppStore";

/**
 * Redirect from the legacy `/$datasetId/compare?optimizations=[id]` URL
 * to the new `/$optimizationId` URL.
 */
const OptimizationCompareRedirect = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [optimizationsIds = []] = useQueryParam("optimizations", JsonParam, {
    updateType: "replaceIn",
  });

  const optimizationId = optimizationsIds?.[0];

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
