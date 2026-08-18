import React, { useEffect } from "react";
import useAppStore, { useActiveWorkspaceName } from "@/store/AppStore";
import { getWorkspaceNameFromUrl } from "@/lib/utils";
import Loader from "@/shared/Loader/Loader";

/**
 * Pair routes (/pair/v1 and the /opik/pair/v1 OSS alias) render outside
 * WorkspaceGuard — they must work without a logged-in session — and carry
 * critical state in search/hash (?workspace=X#payload). Nothing else sets the
 * active workspace on these routes, so this guard lifts it out of the URL.
 *
 * The `v1` in the path is the SDK pairing protocol version, unrelated to the
 * removed Opik V1 UI.
 */
const PairRouteWorkspaceGuard: React.FC<{ children: React.ReactNode }> = ({
  children,
}) => {
  const workspaceFromUrl = getWorkspaceNameFromUrl();
  const active = useActiveWorkspaceName();
  const pendingWorkspace = Boolean(
    workspaceFromUrl && workspaceFromUrl !== active,
  );

  useEffect(() => {
    if (pendingWorkspace && workspaceFromUrl) {
      useAppStore.getState().setActiveWorkspaceName(workspaceFromUrl);
    }
  }, [pendingWorkspace, workspaceFromUrl]);

  // Hold only while a workspace named in the URL has yet to be applied. When
  // the URL carries no workspace there is nothing to wait for, so render and
  // let the pairing page report the missing parameter. The previous guard also
  // waited on a version lookup, which left such URLs on a permanent loader.
  if (pendingWorkspace) return <Loader />;

  return children;
};

export default PairRouteWorkspaceGuard;
