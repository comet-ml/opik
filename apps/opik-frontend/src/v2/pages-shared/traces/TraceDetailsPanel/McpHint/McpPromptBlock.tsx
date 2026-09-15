import React from "react";
import { Check, Copy } from "lucide-react";
import copy from "clipboard-copy";

import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";
import { MCP_PROMPT_COPIED } from "./constants";
import { McpHintEntityType, McpInstallMode } from "./types";

type McpPromptBlockProps = {
  prompt: string;
  installMode: McpInstallMode;
  entityType: McpHintEntityType;
  isCopied: boolean;
  onCopied: () => void;
};

// The block and the confirmation that stands in for it are the same height, so
// the card keeps its size and the docs link below it does not move.
export const MCP_PROMPT_ROW_CLASS = "flex h-7 items-center gap-1.5";

/**
 * The prompt as a code block rather than a tile: on a deployment with no hosted
 * server it is the only route, so it carries the weight of the card.
 *
 * Shows the prompt's opening line and copies all of it. A prompt is a few
 * paragraphs, and a card that tall would cover the traceback it is about.
 */
const McpPromptBlock: React.FunctionComponent<McpPromptBlockProps> = ({
  prompt,
  installMode,
  entityType,
  isCopied,
  onCopied,
}) => {
  const handleCopy = () => {
    copy(prompt);
    trackEvent(OpikEvent.MCP_PROMPT_COPIED, {
      install_mode: installMode,
      entity_type: entityType,
    });
    onCopied();
  };

  if (isCopied) {
    return (
      <div className={`${MCP_PROMPT_ROW_CLASS} text-green-600`}>
        <Check className="size-3.5 shrink-0" />
        <span className="comet-body-xs leading-4">{MCP_PROMPT_COPIED}</span>
      </div>
    );
  }

  return (
    <div
      className={`${MCP_PROMPT_ROW_CLASS} rounded border border-border bg-soft-background px-2`}
    >
      <code className="min-w-0 flex-1 truncate text-xs leading-4 text-muted-slate">
        {prompt}
      </code>
      <TooltipWrapper
        content="Copies the prompt. Paste it into any coding agent and it does the rest."
        nonInteractive
      >
        <button
          type="button"
          aria-label="Copy the prompt"
          onClick={handleCopy}
          data-testid="mcp-route-prompt"
          className="shrink-0 text-light-slate hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          <Copy className="size-3" />
        </button>
      </TooltipWrapper>
    </div>
  );
};

export default McpPromptBlock;
