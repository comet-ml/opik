import React, { useEffect } from "react";
import useAppStore, {
  useActiveWorkspaceName,
  useWorkspaceVersion,
} from "@/store/AppStore";
import useWorkspaceVersionQuery from "@/api/workspaces/useWorkspaceVersion";
import {
  getNewExperienceOptIn,
  getVersionOverride,
  navigateToWorkspaceRoot,
  setCachedWorkspaceVersion,
} from "@/lib/workspaceVersion";

const VERSION_RELOAD_PREFIX = "opik-version-reload:";
const MAX_RELOADS = 2;

type WorkspaceVersionResolverProps = {
  children: React.ReactNode;
};

/**
 * Renders children immediately using the Gate's optimistic version guess
 * (override > opt-in > per-workspace cache > default v2) and verifies
 * against `/workspaces/versions` asynchronously. On mismatch, the
 * `useEffect` below reloads so the Gate picks the correct App next time
 * (bounded by MAX_RELOADS).
 *
 * We deliberately do NOT gate rendering on `isLoading`. Doing so used to
 * hide the whole tree behind a Loader for 600-2000ms on every load, which
 * sat on the LCP critical path. The mismatch window is the same either
 * way — today's Loader vs. tomorrow's brief wrong-version render — so
 * we'd rather pay that cost only when mismatch actually happens (rare:
 * cache is correct for ~95% of returning users, and new signups default
 * to v2 which is the only version new workspaces get). See OPIK-6150.
 */
const WorkspaceVersionResolver: React.FC<WorkspaceVersionResolverProps> = ({
  children,
}) => {
  const override = getVersionOverride();
  const optIn = getNewExperienceOptIn();
  const gateVersion = useWorkspaceVersion();
  const workspaceName = useActiveWorkspaceName();

  const { data: apiVersion } = useWorkspaceVersionQuery();
  const resolvedVersion = override ?? (optIn ? "v2" : apiVersion);

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
        // Navigate to the workspace root rather than reloading the current
        // URL: the other App's router may have already rewritten the URL to
        // something that only makes sense in its version. Landing on the
        // workspace root lets the correct version's router route to home.
        navigateToWorkspaceRoot(workspaceName);
      }
    } else {
      sessionStorage.removeItem(reloadKey);
    }
  }, [apiVersion, resolvedVersion, gateVersion, workspaceName]);

  return children;
};

export default WorkspaceVersionResolver;
