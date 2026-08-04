import React, { useEffect, useRef } from "react";
import useAppStore, {
  useActiveWorkspaceName,
  useWorkspaceVersion,
  WorkspaceVersion,
} from "@/store/AppStore";
import useWorkspaceVersionQuery from "@/api/workspaces/useWorkspaceVersion";
import { setCachedWorkspaceVersion } from "@/lib/workspaceVersion";

const VERSION_RELOAD_PREFIX = "opik-version-reload:";
const MAX_RELOADS = 2;

type WorkspaceVersionResolverProps = {
  children: React.ReactNode;
};

/**
 * Opik V2 is the only supported experience, so the workspace version is pinned
 * to v2 (see resolveSyncWorkspaceVersion) instead of being determined from the
 * backend — the `/workspaces/versions` endpoint has been removed. This component
 * is retained until the V1 UI is deleted (tracked separately); it still writes
 * v2 to the store and the localStorage cache, but the mismatch-driven reload path
 * below is now unreachable because the version is constant.
 */
const WorkspaceVersionResolver: React.FC<WorkspaceVersionResolverProps> = ({
  children,
}) => {
  const gateVersion = useWorkspaceVersion();
  const workspaceName = useActiveWorkspaceName();

  const originalUrlByWorkspaceRef = useRef<Record<string, string>>({});
  if (
    workspaceName &&
    originalUrlByWorkspaceRef.current[workspaceName] === undefined
  ) {
    originalUrlByWorkspaceRef.current[workspaceName] = window.location.href;
  }

  const { data: apiVersion } = useWorkspaceVersionQuery();
  const resolvedVersion: WorkspaceVersion = "v2";

  useEffect(() => {
    if (!resolvedVersion || !workspaceName) return;

    const store = useAppStore.getState();
    store.setWorkspaceVersion(resolvedVersion);
    if (apiVersion) {
      store.setDetectedWorkspaceVersion(apiVersion);
      setCachedWorkspaceVersion(workspaceName, apiVersion);
    }

    const reloadKey = VERSION_RELOAD_PREFIX + workspaceName;

    if (gateVersion && resolvedVersion !== gateVersion) {
      const reloadCount = Number(sessionStorage.getItem(reloadKey) || "0");
      if (reloadCount < MAX_RELOADS) {
        sessionStorage.setItem(reloadKey, String(reloadCount + 1));
        const target =
          originalUrlByWorkspaceRef.current[workspaceName] ??
          window.location.href;
        window.location.replace(target);
      }
    } else {
      sessionStorage.removeItem(reloadKey);
      delete originalUrlByWorkspaceRef.current[workspaceName];
    }
  }, [apiVersion, resolvedVersion, gateVersion, workspaceName]);

  return children;
};

export default WorkspaceVersionResolver;
