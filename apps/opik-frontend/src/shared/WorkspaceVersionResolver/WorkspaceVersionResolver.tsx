import React, { useEffect, useRef } from "react";
import useAppStore, {
  useActiveWorkspaceName,
  useWorkspaceVersion,
} from "@/store/AppStore";
import useWorkspaceVersionQuery from "@/api/workspaces/useWorkspaceVersion";
import {
  getNewExperienceOptIn,
  getVersionOverride,
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
 * against `/workspaces/versions` asynchronously. On mismatch, reloads to
 * the URL the user originally landed on so the Gate mounts the correct
 * App with the original deep-link intact (bounded by MAX_RELOADS).
 *
 * We deliberately do NOT gate rendering on `isLoading`. Doing so used to
 * hide the whole tree behind a Loader for 600-2000ms on every load, which
 * sat on the LCP critical path. See OPIK-6150.
 *
 * Why `window.location.replace(originalUrl)` and not `reload()`:
 * the wrong-version App mounted under us can contain redirect components
 * (e.g. V1CompatRedirect) whose useEffects `navigate({ replace: true })`
 * to a version-specific path before `/versions` returns. A plain
 * `reload()` would rehydrate that mutated URL; replacing with the URL we
 * captured on first render restores the user's original deep-link.
 *
 * Capture is in render (not useEffect) because child-component effects
 * fire after the parent renders — so at capture time no redirect has run.
 * The captured URL is keyed by workspace (so switching workspaces doesn't
 * leak stale URLs) and cleared once verification succeeds (so a later
 * mismatch re-captures fresh rather than reloading to an outdated URL).
 */
const WorkspaceVersionResolver: React.FC<WorkspaceVersionResolverProps> = ({
  children,
}) => {
  const override = getVersionOverride();
  const optIn = getNewExperienceOptIn();
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
