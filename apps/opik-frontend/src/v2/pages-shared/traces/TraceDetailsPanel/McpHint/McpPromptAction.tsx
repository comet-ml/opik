import React, { useEffect, useState } from "react";
import { Check, Copy } from "lucide-react";
import copy from "clipboard-copy";

import { Button } from "@/ui/button";
import { useToast } from "@/ui/use-toast";
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

/**
 * The route for the agent the developer already has open: the only one with no
 * prerequisite, and the only one that covers a client we never enumerated.
 *
 * Says it copied and goes back to offering itself, the way every other copy in
 * the product behaves. Nothing about the card changes, so there is no view to
 * get out of again.
 */
const McpPromptAction: React.FunctionComponent<McpPromptActionProps> = ({
  prompt,
  installMode,
  entityType,
}) => {
  const { toast } = useToast();
  const [hasCopied, setHasCopied] = useState(false);

  useEffect(() => {
    if (!hasCopied) return;
    const timer = setTimeout(() => setHasCopied(false), MCP_COPIED_FEEDBACK_MS);
    return () => clearTimeout(timer);
  }, [hasCopied]);

  const handleClick = () => {
    copy(prompt);
    toast({ description: MCP_COPIED });
    setHasCopied(true);
    // Its own event, not `mcp_connect_clicked`: the funnel's "chose a route"
    // step is the union of the two, and counting this as both would double it.
    trackEvent(OpikEvent.MCP_PROMPT_COPIED, {
      install_mode: installMode,
      entity_type: entityType,
    });
  };

  return (
    <Button
      variant="link"
      size="2xs"
      onClick={handleClick}
      data-testid="mcp-route-prompt"
      className="h-6 shrink-0 px-0 font-mono text-xs text-foreground hover:text-primary"
    >
      <span>{hasCopied ? MCP_COPIED : MCP_PROMPT_ACTION}</span>
      {hasCopied ? (
        <Check className="ml-1 size-3 shrink-0 text-green-600" />
      ) : (
        <Copy className="ml-1 size-3 shrink-0 text-light-slate" />
      )}
    </Button>
  );
};

export default McpPromptAction;
