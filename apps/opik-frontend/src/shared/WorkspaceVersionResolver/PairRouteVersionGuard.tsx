import React, { useEffect } from "react";
import useAppStore, { useActiveWorkspaceName } from "@/store/AppStore";
import { getWorkspaceNameFromUrl } from "@/lib/workspaceVersion";
import WorkspaceVersionResolver from "@/shared/WorkspaceVersionResolver/WorkspaceVersionResolver";
import Loader from "@/shared/Loader/Loader";

/**
 * Pair routes (/pair/v1 and the /opik/pair/v1 OSS alias) render outside
 * WorkspaceGuard — they must work without a logged-in session. That means
 * WorkspaceVersionResolver normally cannot mount on these routes, so a
 * wrong gate guess (e.g. v2 default on a v1 workspace) would render the
 * wrong pair page.
 *
 * This guard seeds activeWorkspaceName from the pair URL's ?workspace=
 * query and mounts WorkspaceVersionResolver so the API-side check +
 * reload-on-mismatch flow runs for pair routes too.
 */
const PairRouteVersionGuard: React.FC<{ children: React.ReactNode }> = ({
  children,
}) => {
  const workspaceFromUrl = getWorkspaceNameFromUrl();
  const active = useActiveWorkspaceName();

  useEffect(() => {
    if (workspaceFromUrl && workspaceFromUrl !== active) {
      useAppStore.getState().setActiveWorkspaceName(workspaceFromUrl);
    }
  }, [workspaceFromUrl, active]);

  // Resolver keys its query off useActiveWorkspaceName and early-returns
  // while it is empty. Show a Loader until the store reflects the URL.
  if (workspaceFromUrl && !active) return <Loader />;

  return <WorkspaceVersionResolver>{children}</WorkspaceVersionResolver>;
};

export default PairRouteVersionGuard;
