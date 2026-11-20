import React, { ReactNode } from "react";
import { PermissionsProvider } from "@/contexts/PermissionsContext";
import { DEFAULT_PERMISSIONS, Permissions } from "@/types/permissions";

type PermissionsTestProviderProps = {
  permissions?: Partial<Permissions>;
  children: ReactNode;
};

// All permissions granted by default; pass overrides to deny specific ones.
const PermissionsTestProvider: React.FC<PermissionsTestProviderProps> = ({
  permissions = {},
  children,
}) => (
  <PermissionsProvider
    value={{
      ...DEFAULT_PERMISSIONS,
      permissions: { ...DEFAULT_PERMISSIONS.permissions, ...permissions },
    }}
  >
    {children}
  </PermissionsProvider>
);

export default PermissionsTestProvider;
