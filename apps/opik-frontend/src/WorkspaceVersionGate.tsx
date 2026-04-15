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
 *   2. Check if the path forces a version (e.g. /pair/* is V2-only)
 *   3. Else parse the workspace name from window.location.pathname
 *   4. If found → fetch version from API with per-request Comet-Workspace header
 *   5. If not found (e.g. root "/") → default to v1 optimistically
 *
 * Steps 1, 2, 5 are synchronous and run at module load, so the first paint
 * already knows which App to render — no Loader flash in the common cases.
 * Only step 4 (workspace in URL, version unknown) falls back to a Loader.
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
  getWorkspaceNameFromPath,
  resolveSyncWorkspaceVersion,
} from "@/lib/workspaceVersion";
import Loader from "@/shared/Loader/Loader";

const V1App = React.lazy(() => import("@/v1/App"));
const V2App = React.lazy(() => import("@/v2/App"));

// Populate the store synchronously at module load so the first render
// already has a version — avoids a Loader flash on /pair/*, root "/", and
// any path with a localStorage version override.
const initialVersion = resolveSyncWorkspaceVersion();
if (initialVersion) {
  useAppStore.getState().setWorkspaceVersion(initialVersion);
}

const WorkspaceVersionGate = () => {
  const version = useWorkspaceVersion();

  useEffect(() => {
    if (useAppStore.getState().workspaceVersion) return;
    const workspaceName = getWorkspaceNameFromPath();
    if (!workspaceName) return;

    let cancelled = false;
    fetchWorkspaceVersion({ workspaceName }).then((resolved) => {
      if (!cancelled) {
        useAppStore.getState().setWorkspaceVersion(resolved);
      }
    });
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
