import React from "react";

import usePluginsStore from "@/store/PluginsStore";
import CliInstallRoutes from "./CliInstallRoutes";
import { McpInstallRoutesProps } from "./types";

// One unconditional store read: the selection changes which component renders,
// never how many hooks this one calls.
const InstallRoutes: React.FunctionComponent<McpInstallRoutesProps> = (
  props,
) => {
  const HostedInstallRoutes = usePluginsStore(
    (state) => state.McpInstallRoutes,
  );
  const Routes = HostedInstallRoutes ?? CliInstallRoutes;

  return <Routes {...props} />;
};

export default InstallRoutes;
