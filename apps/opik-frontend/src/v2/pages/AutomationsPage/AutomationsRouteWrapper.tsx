import React from "react";
import { Outlet, useMatchRoute } from "@tanstack/react-router";
import AutomationsPage from "@/v2/pages/AutomationsPage/AutomationsPage";

const AutomationsRouteWrapper: React.FunctionComponent = () => {
  const matchRoute = useMatchRoute();
  const isRootRoute = matchRoute({
    to: "/$workspaceName/projects/$projectId/automations",
  });

  if (isRootRoute) {
    return <AutomationsPage />;
  }

  return <Outlet />;
};

export default AutomationsRouteWrapper;
