import React, { ReactNode, useMemo } from "react";
import { PermissionsProvider as BasePermissionsProvider } from "@/contexts/PermissionsContext";
import useUserPermission from "./useUserPermission";
import { PermissionsContextValue } from "@/types/permissions";

const PermissionsProvider: React.FC<{ children: ReactNode }> = ({
  children,
}) => {
  const {
    canViewExperiments,
    canViewDashboards,
    canDeleteProjects,
    isPending,
  } = useUserPermission();

  const value: PermissionsContextValue = useMemo(
    () => ({
      permissions: {
        canViewExperiments,
        canViewDashboards,
        canDeleteProjects,
      },
      isPending,
    }),
    [canViewExperiments, canViewDashboards, canDeleteProjects, isPending],
  );

  return (
    <BasePermissionsProvider value={value}>{children}</BasePermissionsProvider>
  );
};

export default PermissionsProvider;
