import React from "react";
import { Copy, ExternalLink } from "lucide-react";

import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { McpInstallRoute, MCP_ROUTE_METHOD } from "./types";
import { MCP_TILE_CLASS } from "./tileStyles";

type McpRouteTileProps = {
  route: McpInstallRoute;
  onUse: (route: McpInstallRoute) => void;
};

/**
 * One install route.
 *
 * A deeplink is an anchor so it keeps the browser's own affordances, but it
 * still reports itself on click: the OS hand-off is unobservable from here, so
 * "clicked" is the only thing we can honestly record — and the confirmation it
 * opens is the recovery path when nothing happened.
 */
const McpRouteTile: React.FunctionComponent<McpRouteTileProps> = ({
  route,
  onUse,
}) => {
  const isDeeplink = route.method === MCP_ROUTE_METHOD.DEEPLINK;
  const TrailingIcon = isDeeplink ? ExternalLink : Copy;

  const handleUse = () => {
    if (!isDeeplink && route.clipboard) {
      navigator.clipboard.writeText(route.clipboard);
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
