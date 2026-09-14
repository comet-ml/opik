import React, { useCallback, useMemo } from "react";

import { useActiveWorkspaceName } from "@/store/AppStore";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";
import McpRouteTile from "./McpRouteTile";
import McpPromptTile from "./McpPromptTile";
import useMcpInstallMode from "./useMcpInstallMode";
import useMcpPromptContext from "./useMcpPromptContext";
import { getMcpServerUrl } from "./serverUrl";
import {
  McpHintTarget,
  McpInstallRoute,
  McpPromptContext,
  McpRouteOutcome,
} from "./types";

type InstallRoutesLayoutProps = {
  routes: McpInstallRoute[];
  buildPrompt: (context: McpPromptContext) => string;
  target: McpHintTarget;
  onRouteUsed: (outcome: McpRouteOutcome) => void;
};

/**
 * Everything the two deployments share: the layout, the reporting, and the
 * prompt's context.
 *
 * A deployment only ever differs in *which* routes it can offer and how the
 * prompt asks for them to be installed — so that is all either side supplies.
 * Keeping the rest here is what stops a fix landing on one deployment and not
 * the other.
 */
const InstallRoutesLayout: React.FunctionComponent<
  InstallRoutesLayoutProps
> = ({ routes, buildPrompt, target, onRouteUsed }) => {
  const installMode = useMcpInstallMode();
  const workspaceName = useActiveWorkspaceName();
  const { projectName } = useMcpPromptContext(target.projectId);

  const prompt = useMemo(
    () =>
      buildPrompt({
        traceId: target.traceId,
        projectName,
        workspaceName,
        serverUrl: getMcpServerUrl(),
      }),
    [buildPrompt, target.traceId, projectName, workspaceName],
  );

  const handleRouteUse = useCallback(
    (route: McpInstallRoute) => {
      trackEvent(OpikEvent.MCP_CONNECT_CLICKED, {
        client: route.client,
        method: route.method,
        install_mode: installMode,
        entity_type: target.entityType,
      });
      onRouteUsed(route);
    },
    [installMode, onRouteUsed, target.entityType],
  );

  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex flex-wrap gap-1.5">
        {routes.map((route) => (
          <McpRouteTile
            key={route.client}
            route={route}
            onUse={handleRouteUse}
          />
        ))}
      </div>
      <McpPromptTile
        prompt={prompt}
        installMode={installMode}
        entityType={target.entityType}
        onUsed={onRouteUsed}
      />
    </div>
  );
};

export default InstallRoutesLayout;
