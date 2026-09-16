import React, { useCallback } from "react";

import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";
import McpRouteTile from "./McpRouteTile";
import McpPromptAction from "./McpPromptAction";
import useMcpInstallMode from "./useMcpInstallMode";
import useMcpPrompt from "./useMcpPrompt";
import {
  MCP_DEEPLINK_FALLBACK_NOTE,
  MCP_PROMPT_PITCH,
  MCP_TILES_LABEL,
} from "./constants";
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
  onCopied: () => void;
};

// Everything the two deployments share. Either side supplies only its routes
// and its prompt builder.
const InstallRoutesLayout: React.FunctionComponent<
  InstallRoutesLayoutProps
> = ({ routes, buildPrompt, target, onRouteUsed, onCopied }) => {
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

      // A copy says so on the tile and leaves the card alone. A deeplink hands
      // off to another app, which cannot be observed from here, so it owes the
      // user a view — with the prompt as the fallback, which only this layer
      // can build.
      if (route.method !== MCP_ROUTE_METHOD.DEEPLINK) {
        onCopied();
        return;
      }

      onRouteUsed({
        ...route,
        note: MCP_DEEPLINK_FALLBACK_NOTE,
        snippet: prompt,
      });
    },
    [installMode, onCopied, onRouteUsed, prompt, target.entityType],
  );

  return (
    <>
      <p className="comet-body-xs mb-1.5 leading-4 text-muted-slate">
        {MCP_TILES_LABEL}
      </p>

      <div className="flex flex-wrap gap-1.5">
        {routes.map((route) => (
          <McpRouteTile
            key={route.client}
            route={route}
            onUse={handleRouteUse}
          />
        ))}
      </div>

      <div className="my-2 h-px w-full bg-border" />

      <div className="flex items-center justify-between gap-2">
        <span className="comet-body-xs leading-4 text-muted-slate">
          {MCP_PROMPT_PITCH}
        </span>
        <McpPromptAction
          prompt={prompt}
          installMode={installMode}
          entityType={target.entityType}
          onCopied={onCopied}
        />
      </div>
    </>
  );
};

export default InstallRoutesLayout;
