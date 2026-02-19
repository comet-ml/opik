import React from "react";
import useUserPermission from "./useUserPermission";
import { WithPermissionsProps } from "@/types/permissions";

export const withPermissions = <P extends WithPermissionsProps>(
  Component: React.ComponentType<P>,
) => {
  const WrappedComponent = (
    props: Omit<P, "canViewExperiments"> & Partial<WithPermissionsProps>,
  ) => {
    const { canViewExperiments } = useUserPermission();

    return (
      <Component {...(props as P)} canViewExperiments={!!canViewExperiments} />
    );
  };

  WrappedComponent.displayName = `withPermissions(${
    Component.displayName || Component.name || "Component"
  })`;

  return WrappedComponent;
};
