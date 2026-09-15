import React, { useCallback } from "react";

import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";
import McpRouteTile from "./McpRouteTile";
import McpPromptTile from "./McpPromptTile";
import useMcpInstallMode from "./useMcpInstallMode";
import useMcpPrompt from "./useMcpPrompt";
import { MCP_DEEPLINK_FALLBACK_NOTE } from "./constants";
import {
  MCP_ROUTE_METHOD,
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

// Everything the two deployments share. Either side supplies only its routes
// and its prompt builder.
const InstallRoutesLayout: React.FunctionComponent<
  InstallRoutesLayoutProps
> = ({ routes, buildPrompt, target, onRouteUsed }) => {
  const installMode = useMcpInstallMode();
  const prompt = useMcpPrompt(target, buildPrompt);

  const handleRouteUse = useCallback(
    (route: McpInstallRoute) => {
      trackEvent(OpikEvent.MCP_CONNECT_CLICKED, {
        client: route.client,
        method: route.method,
        install_mode: installMode,
        entity_type: target.entityType,
      });

      // A deeplink's fallback is the prompt, which only this layer can build,
      // so the route describes the hand-off and the fallback is filled in here.
      onRouteUsed(
        route.method === MCP_ROUTE_METHOD.DEEPLINK
          ? { ...route, note: MCP_DEEPLINK_FALLBACK_NOTE, snippet: prompt }
          : route,
      );
    },
    [installMode, onRouteUsed, prompt, target.entityType],
  );

  return (
    <div className="flex flex-wrap gap-1.5">
      {routes.map((route) => (
        <McpRouteTile key={route.client} route={route} onUse={handleRouteUse} />
      ))}
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
