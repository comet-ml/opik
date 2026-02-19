import React from "react";
import useUserPermission from "./useUserPermission";

type WithCanViewDashboards = {
  canViewDashboards: boolean;
};

export const withDashboardsViewPermission = <P extends WithCanViewDashboards>(
  Component: React.ComponentType<P>,
) => {
  const WrappedComponent = (
    props: Omit<P, "canViewDashboards"> & Partial<WithCanViewDashboards>,
  ) => {
    const { canViewDashboards } = useUserPermission();

    return (
      <Component {...(props as P)} canViewDashboards={!!canViewDashboards} />
    );
  };

  WrappedComponent.displayName = `withDashboardsViewPermission(${
    Component.displayName || Component.name || "Component"
  })`;

  return WrappedComponent;
};
