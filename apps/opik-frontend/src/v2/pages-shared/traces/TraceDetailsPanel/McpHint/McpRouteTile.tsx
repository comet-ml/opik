import React from "react";
import { Copy, ExternalLink } from "lucide-react";
import copy from "clipboard-copy";

import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { McpInstallRoute, MCP_ROUTE_METHOD } from "./types";
import { MCP_TILE_CLASS } from "./tileStyles";

type McpRouteTileProps = {
  route: McpInstallRoute;
  onUse: (route: McpInstallRoute) => void;
};

// A deeplink stays an anchor to keep the browser's own affordances. The OS
// hand-off is unobservable from here, so "clicked" is all we can record.
const McpRouteTile: React.FunctionComponent<McpRouteTileProps> = ({
  route,
  onUse,
}) => {
  const isDeeplink = route.method === MCP_ROUTE_METHOD.DEEPLINK;
  const TrailingIcon = isDeeplink ? ExternalLink : Copy;
  // A custom scheme hands off without navigating, but an https route (VS Code's
  // redirector) would otherwise take the page with it.
  const opensInNewTab = route.href?.startsWith("http") ?? false;

  const handleUse = () => {
    if (!isDeeplink && route.clipboard) {
      copy(route.clipboard);
    }
    onUse(route);
  };

  const content = (
    <>
      <img src={route.logo} alt="" className="size-3 shrink-0" />
      <span className="whitespace-nowrap">{route.label}</span>
      <TrailingIcon className="size-3 shrink-0 text-light-slate" />
    </>
  );

  const className = MCP_TILE_CLASS;

  return (
    <TooltipWrapper content={route.tooltip} nonInteractive>
      {isDeeplink ? (
        <a
          href={route.href}
          onClick={handleUse}
          target={opensInNewTab ? "_blank" : undefined}
          rel={opensInNewTab ? "noreferrer" : undefined}
          className={className}
          data-testid={`mcp-route-${route.client}`}
        >
          {content}
        </a>
      ) : (
        <button
          type="button"
          onClick={handleUse}
          className={className}
          data-testid={`mcp-route-${route.client}`}
        >
          {content}
        </button>
      )}
    </TooltipWrapper>
  );
};

export default McpRouteTile;
