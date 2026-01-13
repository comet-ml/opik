import React, { useEffect } from "react";
import useAppStore from "@/store/AppStore";
import { DEFAULT_WORKSPACE_NAME } from "@/constants/user";
import { useWorkspaceNameFromURL } from "@/hooks/useWorkspaceNameFromURL";
import { Navigate } from "@tanstack/react-router";

type WorkspacePreloaderProps = {
  children: React.ReactNode;
};

const WorkspacePreloader: React.FunctionComponent<WorkspacePreloaderProps> = ({
  children,
}) => {
  const setActiveWorkspaceName = useAppStore(
    (state) => state.setActiveWorkspaceName,
  );

  useEffect(() => {
    setActiveWorkspaceName(DEFAULT_WORKSPACE_NAME);
  }, [setActiveWorkspaceName]);

  const workspaceNameFromURL = useWorkspaceNameFromURL();

  if (workspaceNameFromURL && workspaceNameFromURL !== DEFAULT_WORKSPACE_NAME) {
    return (
      <Navigate
        to="/$workspaceName"
        params={{ workspaceName: DEFAULT_WORKSPACE_NAME }}
      />
    );
  }

  return children;
};

export default WorkspacePreloader;
