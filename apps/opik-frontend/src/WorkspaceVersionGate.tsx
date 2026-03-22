/**
 * Two-level workspace version resolution.
 *
 * Version is per-workspace, but the workspace is only fully resolved inside
 * the router tree (WorkspacePreloader). This creates a circular dependency:
 *   router selection → needs version → needs workspace → needs router
 *
 * We break the circle with two levels of checks:
 *
 * Level 1 — WorkspaceVersionGate (this component, BEFORE the router):
 *   1. Check localStorage override ("opik-version-override") → use immediately
 *   2. Try to parse workspace name from window.location.pathname
 *   3. If found → fetch version from API with per-request Comet-Workspace header
 *   4. If not found (e.g. root "/") → default to v1 optimistically
 *   5. Render the correct App (V1App or V2App) based on resolved version
 *
 * Level 2 — WorkspaceVersionResolver (INSIDE the router, after WorkspacePreloader):
 *   1. Workspace is now fully resolved (auth, access, header set)
 *   2. Fetch version via React Query (reuses existing providers)
 *   3. If version matches what the gate picked → continue
 *   4. If mismatch → hard reload (URL now contains workspace → Level 1 gets it right)
 *
 * This ensures the correct router loads on first paint when workspace is in
 * the URL, and self-corrects via reload in the rare optimistic-default case.
 */
import React, { Suspense, useEffect } from "react";
import useAppStore, { useWorkspaceVersion } from "@/store/AppStore";
import { fetchWorkspaceVersion } from "@/api/workspaces/useWorkspaceVersion";
import {
  DEFAULT_WORKSPACE_VERSION,
  getVersionOverride,
  getWorkspaceNameFromPath,
} from "@/lib/workspaceVersion";
import Loader from "@/shared/Loader/Loader";

const V1App = React.lazy(() => import("@/v1/App"));
const V2App = React.lazy(() => import("@/v2/App"));

const WorkspaceVersionGate = () => {
  const version = useWorkspaceVersion();

  useEffect(() => {
    let cancelled = false;

    async function resolve() {
      const override = getVersionOverride();
      if (override) {
        useAppStore.getState().setWorkspaceVersion(override);
        return;
      }

      const workspaceName = getWorkspaceNameFromPath();
      if (workspaceName) {
        const resolved = await fetchWorkspaceVersion({ workspaceName });
        if (!cancelled) {
          useAppStore.getState().setWorkspaceVersion(resolved);
        }
      } else {
        useAppStore.getState().setWorkspaceVersion(DEFAULT_WORKSPACE_VERSION);
      }
    }
    resolve();

    return () => {
      cancelled = true;
    };
  }, []);

  if (!version) {
    return <Loader />;
  }

  return (
    <Suspense fallback={<Loader />}>
      {version === "v1" ? <V1App /> : <V2App />}
    </Suspense>
  );
};

export default WorkspaceVersionGate;
