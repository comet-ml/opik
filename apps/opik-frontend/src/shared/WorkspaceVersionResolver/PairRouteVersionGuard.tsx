import React, { useEffect } from "react";
import useAppStore, {
  useActiveWorkspaceName,
  useWorkspaceVersion,
} from "@/store/AppStore";
import useWorkspaceVersionQuery from "@/api/workspaces/useWorkspaceVersion";
import {
  getWorkspaceNameFromUrl,
  setCachedWorkspaceVersion,
} from "@/lib/workspaceVersion";
import Loader from "@/shared/Loader/Loader";

const VERSION_RELOAD_PREFIX = "opik-version-reload:";
const MAX_RELOADS = 2;

/**
 * Pair routes (/pair/v1 and the /opik/pair/v1 OSS alias) render outside
 * WorkspaceGuard — they must work without a logged-in session — and carry
 * critical state in search/hash (?workspace=X#payload). This guard sets the
 * active workspace from the URL and renders the pairing page. Since Opik V2 is
 * the only supported experience, the version is always v2 (the backend
 * `/workspaces/versions` lookup has been removed), so the mismatch-driven reload
 * below is unreachable; the guard is kept until the V1 UI is removed separately.
 */
const PairRouteVersionGuard: React.FC<{ children: React.ReactNode }> = ({
  children,
}) => {
  const workspaceFromUrl = getWorkspaceNameFromUrl();
  const active = useActiveWorkspaceName();
  const gateVersion = useWorkspaceVersion();
  const { data: apiVersion } = useWorkspaceVersionQuery();
  const mismatch = Boolean(
    apiVersion && gateVersion && apiVersion !== gateVersion,
  );

  useEffect(() => {
    if (workspaceFromUrl && workspaceFromUrl !== active) {
      useAppStore.getState().setActiveWorkspaceName(workspaceFromUrl);
    }
  }, [workspaceFromUrl, active]);

  useEffect(() => {
    if (!apiVersion || !workspaceFromUrl) return;
    setCachedWorkspaceVersion(workspaceFromUrl, apiVersion);

    const reloadKey = VERSION_RELOAD_PREFIX + workspaceFromUrl;
    if (mismatch) {
      const reloadCount = Number(sessionStorage.getItem(reloadKey) || "0");
      if (reloadCount < MAX_RELOADS) {
        sessionStorage.setItem(reloadKey, String(reloadCount + 1));
        window.location.reload();
      } else {
        useAppStore.getState().setWorkspaceVersion(apiVersion);
        sessionStorage.removeItem(reloadKey);
      }
    } else {
      sessionStorage.removeItem(reloadKey);
    }
  }, [apiVersion, workspaceFromUrl, mismatch]);

  if (!active || !apiVersion || mismatch) return <Loader />;
  return children;
};

export default PairRouteVersionGuard;
