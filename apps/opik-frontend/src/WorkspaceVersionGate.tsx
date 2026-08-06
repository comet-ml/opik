/**
 * Selects which App to mount from the workspace version in the store.
 *
 * Opik V2 is the only supported experience: resolveSyncWorkspaceVersion() always
 * returns v2, so V2App is always mounted. The V1App branch and the downstream
 * WorkspaceVersionResolver are kept until the V1 UI is removed (tracked
 * separately) but are effectively inert — there is no backend version lookup
 * (the `/workspaces/versions` endpoint has been removed) and nothing that
 * resolves a workspace to v1.
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
