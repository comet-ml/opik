import React from "react";

import usePluginsStore from "@/store/PluginsStore";
import LocalInstallRoutes from "./LocalInstallRoutes";
import { McpInstallRoutesProps } from "./types";

/**
 * Picks the install routes for this deployment.
 *
 * The seam is drawn around the routes and nothing else. Everything that makes
 * the hint work — the rail, the reveal, the popover, the hover grace, the
 * confirmation — stays here and is shared, because two copies of that is how a
 * fix lands on one deployment and not the other.
 *
 * A single unconditional store read: the selection changes which component
 * renders, never how many hooks this one calls.
 */
const InstallRoutes: React.FunctionComponent<McpInstallRoutesProps> = (
  props,
) => {
  const HostedInstallRoutes = usePluginsStore(
    (state) => state.McpInstallRoutes,
  );
  const Routes = HostedInstallRoutes ?? LocalInstallRoutes;

  return <Routes {...props} />;
};

export default InstallRoutes;
