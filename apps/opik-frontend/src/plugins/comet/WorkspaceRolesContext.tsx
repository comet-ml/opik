import { createContext, useContext, ReactNode, useMemo } from "react";
import { WorkspaceRole } from "./types";

interface WorkspaceRolesContextValue {
  roles: WorkspaceRole[];
  isLoading: boolean;
}

const WorkspaceRolesContext = createContext<WorkspaceRolesContextValue | null>(
  null,
);

interface WorkspaceRolesProviderProps {
  children: ReactNode;
  roles: WorkspaceRole[];
  isLoading: boolean;
}

export const WorkspaceRolesProvider = ({
  children,
  roles,
  isLoading,
}: WorkspaceRolesProviderProps) => {
  const value = useMemo(() => ({ roles, isLoading }), [roles, isLoading]);
  return (
    <WorkspaceRolesContext.Provider value={value}>
      {children}
    </WorkspaceRolesContext.Provider>
  );
};

export const useWorkspaceRolesContext = () => {
  const context = useContext(WorkspaceRolesContext);

  if (context === null) {
    return { roles: [], isLoading: false };
  }

  return context;
};
