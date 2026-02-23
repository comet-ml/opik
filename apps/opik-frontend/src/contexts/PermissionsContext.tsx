import React, { createContext, useContext, ReactNode } from "react";
import { PermissionsContextValue } from "@/types/permissions";

const PermissionsContext = createContext<PermissionsContextValue | undefined>(
  undefined,
);

export const PermissionsProvider: React.FC<{
  children: ReactNode;
  value: PermissionsContextValue;
}> = ({ children, value }) => {
  return (
    <PermissionsContext.Provider value={value}>
      {children}
    </PermissionsContext.Provider>
  );
};

export const usePermissions = (): PermissionsContextValue => {
  const context = useContext(PermissionsContext);
  if (context === undefined) {
    throw new Error("usePermissions must be used within a PermissionsProvider");
  }
  return context;
};
