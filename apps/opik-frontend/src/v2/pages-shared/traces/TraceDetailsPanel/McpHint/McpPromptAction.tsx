import React, { useEffect, useState } from "react";
import { Check, Copy } from "lucide-react";
import copy from "clipboard-copy";

import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";
import {
  MCP_COPIED,
  MCP_COPIED_FEEDBACK_MS,
  MCP_PROMPT_ACTION,
} from "./constants";
import { McpHintEntityType, McpInstallMode } from "./types";

type McpPromptActionProps = {
  prompt: string;
  installMode: McpInstallMode;
  entityType: McpHintEntityType;
};

// The same shape as the docs link below it, which is what the design asks for.
const ACTION_CLASS =
  "comet-body-xs inline-flex shrink-0 items-center gap-1 leading-4 text-foreground underline underline-offset-2 hover:text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring";

/**
 * The route for the agent the developer already has open: the only one with no
 * prerequisite, and the only one that covers a client we never enumerated.
 *
 * Says it copied for a moment and goes back to offering itself. No toast: the
 * message is already here, next to the pointer that asked for it.
 */
const McpPromptAction: React.FunctionComponent<McpPromptActionProps> = ({
  prompt,
  installMode,
  entityType,
}) => {
  const [hasCopied, setHasCopied] = useState(false);

  useEffect(() => {
    if (!hasCopied) return;
    const timer = setTimeout(() => setHasCopied(false), MCP_COPIED_FEEDBACK_MS);
    return () => clearTimeout(timer);
  }, [hasCopied]);

  const handleClick = () => {
    copy(prompt);
    setHasCopied(true);
    // Its own event, not `mcp_connect_clicked`: the funnel's "chose a route"
    // step is the union of the two, and counting this as both would double it.
    trackEvent(OpikEvent.MCP_PROMPT_COPIED, {
      install_mode: installMode,
      entity_type: entityType,
    });
  };

  // Not a button while it says so: there is nothing to press, and a hover
  // effect on a message reads as one.
  if (hasCopied) {
    return (
      <span
        data-testid="mcp-route-prompt-copied"
        className="comet-body-xs inline-flex shrink-0 items-center gap-1 leading-4 text-green-600"
      >
        <Check className="size-3 shrink-0" />
        {MCP_COPIED}
      </span>
    );
  }

  return (
    <button
      type="button"
      onClick={handleClick}
      data-testid="mcp-route-prompt"
      className={ACTION_CLASS}
    >
      {MCP_PROMPT_ACTION}
      <Copy className="size-3 shrink-0" />
    </button>
  );
};

export default McpPromptAction;
