import React from "react";
import useUserPermission from "./useUserPermission";
import { WithPermissionsProps } from "@/types/permissions";

export const withPermissions = <P extends WithPermissionsProps>(
  Component: React.ComponentType<P>,
) => {
  const WrappedComponent = (
    props: Omit<P, "canViewExperiments"> & Partial<WithPermissionsProps>,
  ) => {
    const { canViewExperiments, canViewDashboards } = useUserPermission();

    return (
      <Component
        {...(props as P)}
        canViewExperiments={!!canViewExperiments}
        canViewDashboards={!!canViewDashboards}
      />
    );
  };

  WrappedComponent.displayName = `withPermissions(${
    Component.displayName || Component.name || "Component"
  })`;

  return WrappedComponent;
};
