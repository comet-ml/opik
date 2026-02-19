import React from "react";
import useUserPermission from "./useUserPermission";

type WithCanViewExperiments = {
  canViewExperiments: boolean;
};

export const withExperimentsViewPermission = <P extends WithCanViewExperiments>(
  Component: React.ComponentType<P>,
) => {
  const WrappedComponent = (
    props: Omit<P, "canViewExperiments"> & Partial<WithCanViewExperiments>,
  ) => {
    const { canViewExperiments } = useUserPermission();

    return (
      <Component {...(props as P)} canViewExperiments={!!canViewExperiments} />
    );
  };

  WrappedComponent.displayName = `withExperimentsViewPermission(${
    Component.displayName || Component.name || "Component"
  })`;

  return WrappedComponent;
};
