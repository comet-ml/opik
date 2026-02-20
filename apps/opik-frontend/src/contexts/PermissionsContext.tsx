import React, { createContext, useContext, ReactNode } from "react";
import { Permissions } from "@/types/permissions";

const PermissionsContext = createContext<Permissions | undefined>(undefined);

export const PermissionsProvider: React.FC<{
  children: ReactNode;
  permissions: Permissions;
}> = ({ children, permissions }) => {
  return (
    <PermissionsContext.Provider value={permissions}>
      {children}
    </PermissionsContext.Provider>
  );
};

export const usePermissions = (): Permissions => {
  const context = useContext(PermissionsContext);
  if (context === undefined) {
    throw new Error("usePermissions must be used within a PermissionsProvider");
  }
  return context;
};
