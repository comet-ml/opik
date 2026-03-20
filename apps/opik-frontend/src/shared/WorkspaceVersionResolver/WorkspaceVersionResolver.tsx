import React, { useEffect } from "react";
import useAppStore, {
  useActiveWorkspaceName,
  useWorkspaceVersion,
} from "@/store/AppStore";
import useWorkspaceVersionQuery from "@/api/workspaces/useWorkspaceVersion";
import { getVersionOverride } from "@/lib/workspaceVersion";
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
  const gateVersion = useWorkspaceVersion();
  const workspaceName = useActiveWorkspaceName();

  const { data: apiVersion, isLoading } = useWorkspaceVersionQuery();
  const resolvedVersion = override ?? apiVersion;

  useEffect(() => {
    if (!resolvedVersion || !workspaceName) return;

    useAppStore.getState().setWorkspaceVersion(resolvedVersion);

    const reloadKey = VERSION_RELOAD_PREFIX + workspaceName;

    if (gateVersion && resolvedVersion !== gateVersion) {
      const reloadCount = Number(sessionStorage.getItem(reloadKey) || "0");
      if (reloadCount < MAX_RELOADS) {
        sessionStorage.setItem(reloadKey, String(reloadCount + 1));
        window.location.reload();
      }
    } else {
      sessionStorage.removeItem(reloadKey);
    }
  }, [resolvedVersion, gateVersion, workspaceName]);

  if (!override && isLoading) {
    return <Loader />;
  }

  return children;
};

export default WorkspaceVersionResolver;
