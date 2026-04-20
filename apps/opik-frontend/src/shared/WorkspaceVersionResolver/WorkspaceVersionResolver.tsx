import React, { useEffect } from "react";
import useAppStore, {
  useActiveWorkspaceName,
  useWorkspaceVersion,
} from "@/store/AppStore";
import useWorkspaceVersionQuery from "@/api/workspaces/useWorkspaceVersion";
import {
  getVersionOverride,
  setCachedWorkspaceVersion,
} from "@/lib/workspaceVersion";
import Loader from "@/shared/Loader/Loader";

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
    setCachedWorkspaceVersion(workspaceName, resolvedVersion);
    if (resolvedVersion !== gateVersion) {
      useAppStore.getState().setWorkspaceVersion(resolvedVersion);
    }
  }, [resolvedVersion, gateVersion, workspaceName]);

  if (!override && isLoading) {
    return <Loader />;
  }

  return children;
};

export default WorkspaceVersionResolver;
