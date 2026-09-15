import React from "react";
import { Copy, Sparkles } from "lucide-react";

import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";
import { McpHintEntityType, McpInstallMode, McpRouteOutcome } from "./types";
import { MCP_TILE_CLASS } from "./tileStyles";

type McpPromptTileProps = {
  prompt: string;
  installMode: McpInstallMode;
  entityType: McpHintEntityType;
  onUsed: (outcome: McpRouteOutcome) => void;
};

/**
 * The route for the agent the developer already has open.
 *
 * First-class rather than a footnote, because it is the only route with no
 * prerequisite they might be missing: a deeplink needs a registered OS handler,
 * the CLI needs `uv`, a native command needs that client's CLI — while having a
 * coding agent open is the premise of the whole feature. It is also the only
 * route that covers editors we never enumerated.
 */
const McpPromptTile: React.FunctionComponent<McpPromptTileProps> = ({
  prompt,
  installMode,
  entityType,
  onUsed,
}) => {
  const handlePromptCopy = () => {
    navigator.clipboard.writeText(prompt);
    // Its own event, and deliberately not `mcp_connect_clicked`: counting it as
    // both would double-count the funnel's "chose a route" step. Whoever builds
    // the funnel needs the union of the two.
    trackEvent(OpikEvent.MCP_PROMPT_COPIED, {
      install_mode: installMode,
      entity_type: entityType,
    });
    onUsed({
      confirmation: "Copied — paste it into your coding agent",
      note: "It will set up the server, then debug this trace for you.",
    });
  };

  return (
    <TooltipWrapper
      content="Copies a prompt. Paste it into any coding agent and it does the rest."
      nonInteractive
    >
      <button
        type="button"
        onClick={handlePromptCopy}
        data-testid="mcp-route-prompt"
        className={MCP_TILE_CLASS}
      >
        <Sparkles className="size-3 shrink-0 text-[var(--color-ollie)]" />
        <span className="whitespace-nowrap">Prompt</span>
        <Copy className="size-3 shrink-0 text-light-slate" />
      </button>
    </TooltipWrapper>
  );
};

export default McpPromptTile;
