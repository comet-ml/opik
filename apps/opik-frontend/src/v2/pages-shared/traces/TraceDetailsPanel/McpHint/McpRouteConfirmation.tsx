import React, { useState } from "react";
import { Check, Copy } from "lucide-react";
import copy from "clipboard-copy";

import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { McpRouteOutcome } from "./types";
import { MCP_PROMPT_ROW_CLASS } from "./McpPromptBlock";

type McpRouteConfirmationProps = {
  route: McpRouteOutcome;
};

/** What the card shows once the user has left through one of the routes. */
const McpRouteConfirmation: React.FunctionComponent<
  McpRouteConfirmationProps
> = ({ route }) => {
  const [hasRecopied, setHasRecopied] = useState(false);

  const handleRecopy = () => {
    if (!route.snippet) return;
    copy(route.snippet);
    setHasRecopied(true);
  };

  return (
    <div className="flex flex-col gap-2">
      {/* No tick on an opened deeplink: nothing is confirmed, the hand-off is
          simply under way, and it cannot be observed from here. */}
      <div className="flex items-start gap-1.5 text-green-600">
        {route.kind === "copied" && (
          <Check className="mt-0.5 size-3.5 shrink-0" />
        )}
        <span className="comet-body-xs leading-4">{route.confirmation}</span>
      </div>

      {route.note && (
        <p className="comet-body-xs leading-4 text-foreground">{route.note}</p>
      )}

      {/* One line, like the block it stands in for: the fallback is the whole
          prompt, and a card that tall would cover the traceback it is about. */}
      {route.snippet && (
        <div
          className={`${MCP_PROMPT_ROW_CLASS} rounded border border-border bg-soft-background px-2`}
        >
          <code className="min-w-0 flex-1 truncate text-xs leading-4 text-muted-slate">
            {route.snippet}
          </code>
          <TooltipWrapper
            content={hasRecopied ? "Copied" : "Copy"}
            nonInteractive
          >
            <button
              type="button"
              aria-label="Copy it"
              onClick={handleRecopy}
              className="shrink-0 text-light-slate hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            >
              {hasRecopied ? (
                <Check className="size-3" />
              ) : (
                <Copy className="size-3" />
              )}
            </button>
          </TooltipWrapper>
        </div>
      )}
    </div>
  );
};

export default McpRouteConfirmation;
