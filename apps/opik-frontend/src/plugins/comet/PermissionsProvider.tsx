import React, { ReactNode, useMemo } from "react";
import { PermissionsProvider as BasePermissionsProvider } from "@/contexts/PermissionsContext";
import useUserPermission from "./useUserPermission";
import { Permissions } from "@/types/permissions";

const PermissionsProvider: React.FC<{ children: ReactNode }> = ({
  children,
}) => {
  const { canViewExperiments } = useUserPermission();

  const permissions: Permissions = useMemo(
    () => ({
      canViewExperiments,
    }),
    [canViewExperiments],
  );

  return (
    <BasePermissionsProvider permissions={permissions}>
      {children}
    </BasePermissionsProvider>
  );
};

export default PermissionsProvider;
