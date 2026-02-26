import React, { ReactNode, useMemo } from "react";
import { PermissionsProvider as BasePermissionsProvider } from "@/contexts/PermissionsContext";
import useUserPermission from "./useUserPermission";
import { PermissionsContextValue } from "@/types/permissions";

const PermissionsProvider: React.FC<{ children: ReactNode }> = ({
  children,
}) => {
  const { canViewExperiments, canViewDashboards, canViewDatasets, isPending } =
    useUserPermission();

  const value: PermissionsContextValue = useMemo(
    () => ({
      permissions: {
        canViewExperiments,
        canViewDashboards,
        canViewDatasets,
      },
      isPending,
    }),
    [canViewExperiments, canViewDashboards, canViewDatasets, isPending],
  );

  return (
    <BasePermissionsProvider value={value}>{children}</BasePermissionsProvider>
  );
};

export default PermissionsProvider;
