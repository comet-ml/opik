import React, { useEffect } from "react";
import useAppStore, {
  useActiveWorkspaceName,
  useWorkspaceVersion,
} from "@/store/AppStore";
import useWorkspaceVersionQuery from "@/api/workspaces/useWorkspaceVersion";
import {
  clearVersionOverride,
  getNewExperienceOptIn,
  getVersionOverride,
  getWorkspaceHomeUrl,
  setNewExperienceOptIn,
} from "@/lib/workspaceVersion";
import Loader from "@/shared/Loader/Loader";

const VERSION_RELOAD_PREFIX = "opik-version-reload:";
const MAX_RELOADS = 2;

type WorkspaceVersionResolverProps = {
  children: React.ReactNode;
};

const WorkspaceVersionResolver: React.FC<WorkspaceVersionResolverProps> = ({
  children,
}) => {
  const override = getVersionOverride();
  const optIn = getNewExperienceOptIn();
  const gateVersion = useWorkspaceVersion();
  const workspaceName = useActiveWorkspaceName();

  const { data: apiVersion, isLoading } = useWorkspaceVersionQuery();
  // Priority: explicit override wins; once the backend reports v2 the workspace
  // is fully migrated and v2 always renders; otherwise the user's opt-in to the
  // new experience upgrades a v1 workspace to v2; default to whatever the
  // backend returned.
  const resolvedVersion = override
    ? override
    : apiVersion === "v2"
      ? "v2"
      : optIn
        ? "v2"
        : apiVersion;

  useEffect(() => {
    if (!resolvedVersion || !workspaceName) return;

    if (apiVersion === "v2") {
      if (override) clearVersionOverride();
      if (optIn) setNewExperienceOptIn(false);
    }

    useAppStore.getState().setWorkspaceVersion(resolvedVersion);

    const reloadKey = VERSION_RELOAD_PREFIX + workspaceName;

    if (gateVersion && resolvedVersion !== gateVersion) {
      const reloadCount = Number(sessionStorage.getItem(reloadKey) || "0");
      if (reloadCount < MAX_RELOADS) {
        sessionStorage.setItem(reloadKey, String(reloadCount + 1));
        // URL structure differs between v1 and v2, so redirect to workspace
        // home instead of reloading the current path.
        window.location.href = getWorkspaceHomeUrl(workspaceName);
      }
    } else {
      sessionStorage.removeItem(reloadKey);
    }
  }, [
    apiVersion,
    override,
    optIn,
    resolvedVersion,
    gateVersion,
    workspaceName,
  ]);

  if (!override && !optIn && isLoading) {
    return <Loader />;
  }

  return children;
};

export default WorkspaceVersionResolver;
