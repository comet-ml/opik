/**
 * Mounts the Opik application.
 *
 * V2 is the only experience; the V1 UI has been removed. The store write is
 * retained because WorkspaceVersionResolver, mounted downstream from the V2
 * workspace guard, still reads the value. Both go when the workspace-version
 * machinery is retired (tracked separately), at which point this file goes too
 * and V2App is mounted directly from the entry point.
 */
import React, { Suspense } from "react";
import useAppStore from "@/store/AppStore";
import { resolveSyncWorkspaceVersion } from "@/lib/workspaceVersion";
import Loader from "@/shared/Loader/Loader";

const V2App = React.lazy(() => import("@/v2/App"));

useAppStore.getState().setWorkspaceVersion(resolveSyncWorkspaceVersion());

const WorkspaceVersionGate = () => {
  return (
    <Suspense fallback={<Loader />}>
      <V2App />
    </Suspense>
  );
};

export default WorkspaceVersionGate;
