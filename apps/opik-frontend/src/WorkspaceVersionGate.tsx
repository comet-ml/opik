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
 *   resolveSyncWorkspaceVersion() always returns a version synchronously
 *   from: override > per-workspace cache > DEFAULT_WORKSPACE_VERSION.
 *   First paint renders the chosen App immediately — no Loader, no API
 *   call at this stage.
 *
 * Level 2 — WorkspaceVersionResolver (INSIDE the router, after WorkspacePreloader):
 *   1. Workspace is now fully resolved (auth, access, header set)
 *   2. Fetch version via React Query (reuses existing providers)
 *   3. Write the resolved version to the localStorage cache
 *   4. If it disagrees with what the gate picked → hard reload so the next
 *      module load picks the correct App synchronously from the cache.
 *
 * Reload is required because each App instantiates its own TanStack Router
 * at module scope. Swapping apps in place leaves the inactive router's URL
 * state stale and causes the two WorkspacePreloaders to disagree on the
 * active workspace. A full reload re-evaluates modules with the current
 * browser URL and resets both routers to a consistent initial state.
 */
import React, { Suspense } from "react";
import useAppStore, { useWorkspaceVersion } from "@/store/AppStore";
import { resolveSyncWorkspaceVersion } from "@/lib/workspaceVersion";
import Loader from "@/shared/Loader/Loader";

const V1App = React.lazy(() => import("@/v1/App"));
const V2App = React.lazy(() => import("@/v2/App"));

useAppStore.getState().setWorkspaceVersion(resolveSyncWorkspaceVersion());

const WorkspaceVersionGate = () => {
  const version = useWorkspaceVersion();

  return (
    <Suspense fallback={<Loader />}>
      {version === "v1" ? <V1App /> : <V2App />}
    </Suspense>
  );
};

export default WorkspaceVersionGate;
