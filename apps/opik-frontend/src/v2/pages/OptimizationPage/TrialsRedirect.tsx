import { useEffect, useRef } from "react";
import { useNavigate, useParams, useRouterState } from "@tanstack/react-router";

import { useActiveWorkspaceName } from "@/store/AppStore";
import Loader from "@/shared/Loader/Loader";

/**
 * Rewrite the legacy /trials query params to the names the sidebar over the run
 * overview uses:
 *  - `page` drove the old trial-items table; the overview's `page` now paginates
 *    the main trials table, and trial items read `itemsPage`.
 *  - `diff=true` opened the prompt diff; the sidebar reads `trialTab=diff`.
 * Everything else (`trials`, `trialNumber`, …) already matches 1:1.
 */
const translateLegacySearch = (search: Record<string, unknown>) => {
  const next = { ...search };

  if ("page" in next) {
    if (next.itemsPage === undefined) next.itemsPage = next.page;
    delete next.page;
  }

  if ("diff" in next) {
    const isDiff = next.diff === true || next.diff === "true";
    if (isDiff && next.trialTab === undefined) next.trialTab = "diff";
    delete next.diff;
  }

  return next;
};

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
    const nextSearch = translateLegacySearch(search);
    navigate({
      to: "/$workspaceName/projects/$projectId/optimizations/$optimizationId",
      params: { workspaceName, projectId, optimizationId },
      search: Object.keys(nextSearch).length > 0 ? nextSearch : undefined,
      replace: true,
    });
  }, [workspaceName, projectId, optimizationId, search, navigate]);

  return <Loader />;
};

export default TrialsRedirect;
