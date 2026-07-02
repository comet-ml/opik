import { useEffect, useRef } from "react";
import { useNavigate, useParams, useRouterState } from "@tanstack/react-router";

import { useActiveWorkspaceName } from "@/store/AppStore";
import Loader from "@/shared/Loader/Loader";

/**
 * The trial view is a sidebar over the run overview now. Old
 * /optimizations/$optimizationId/trials deep links redirect onto the overview
 * URL with their query params (`trials`, `trialNumber`, …) intact — a
 * non-empty `trials` param opens the sidebar there.
 */
const TrialsRedirect = () => {
  const workspaceName = useActiveWorkspaceName();
  const { projectId, optimizationId } = useParams({ strict: false }) as {
    projectId?: string;
    optimizationId?: string;
  };
  const navigate = useNavigate();
  const search = useRouterState({
    select: (s) => s.location.search as Record<string, unknown>,
  });
  const hasRedirected = useRef(false);

  useEffect(() => {
    if (!projectId || !optimizationId || hasRedirected.current) return;
    hasRedirected.current = true;
    navigate({
      to: "/$workspaceName/projects/$projectId/optimizations/$optimizationId",
      params: { workspaceName, projectId, optimizationId },
      search: Object.keys(search).length > 0 ? search : undefined,
      replace: true,
    });
  }, [workspaceName, projectId, optimizationId, search, navigate]);

  return <Loader />;
};

export default TrialsRedirect;
