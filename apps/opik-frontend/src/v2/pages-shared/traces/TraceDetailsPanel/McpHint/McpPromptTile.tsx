import React from "react";
import { Copy, Sparkles } from "lucide-react";
import copy from "clipboard-copy";

import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";
import { McpHintEntityType, McpInstallMode, McpRouteOutcome } from "./types";
import { MCP_PROMPT_COPIED } from "./constants";
import { MCP_TILE_CLASS } from "./tileStyles";

type McpPromptTileProps = {
  prompt: string;
  installMode: McpInstallMode;
  entityType: McpHintEntityType;
  onUsed: (outcome: McpRouteOutcome) => void;
};

/**
 * The route for the agent the developer already has open: the only one with no
 * prerequisite, and the only one that covers editors we never enumerated.
 */
const McpPromptTile: React.FunctionComponent<McpPromptTileProps> = ({
  prompt,
  installMode,
  entityType,
  onUsed,
}) => {
  const handlePromptCopy = () => {
    copy(prompt);
    // Not `mcp_connect_clicked`: the funnel's "chose a route" step is the union
    // of the two, and counting this as both would double it.
    trackEvent(OpikEvent.MCP_PROMPT_COPIED, {
      install_mode: installMode,
      entity_type: entityType,
    });
    onUsed({ kind: "copied", confirmation: MCP_PROMPT_COPIED });
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
