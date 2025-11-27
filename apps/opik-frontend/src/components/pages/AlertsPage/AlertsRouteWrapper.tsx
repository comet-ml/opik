import React from "react";
import { Outlet, useMatchRoute } from "@tanstack/react-router";
import AlertsPage from "@/components/pages/AlertsPage/AlertsPage";

const AlertsRouteWrapper: React.FunctionComponent = () => {
  const matchRoute = useMatchRoute();
  const isAlertsRootRoute = matchRoute({
    to: "/$workspaceName/alerts",
  });

  if (isAlertsRootRoute) {
    return <AlertsPage />;
  }

  return <Outlet />;
};

export default AlertsRouteWrapper;
