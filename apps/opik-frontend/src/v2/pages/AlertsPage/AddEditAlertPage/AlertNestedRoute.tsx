import React from "react";
import { Navigate, Outlet, useMatchRoute } from "@tanstack/react-router";
import useAppStore from "@/store/AppStore";

const AlertNestedRoute: React.FunctionComponent = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const matchRoute = useMatchRoute();
  const isAlertsRootRoute = matchRoute({
    to: "/$workspaceName/alerts",
  });

  if (isAlertsRootRoute) {
    return (
      <Navigate to={`/${workspaceName}/configuration?tab=alerts`} replace />
    );
  }

  return <Outlet />;
};

export default AlertNestedRoute;
