import React, { useState } from "react";
import { Check, Copy } from "lucide-react";
import copy from "clipboard-copy";

import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { McpRouteOutcome } from "./types";

type McpRouteConfirmationProps = {
  route: McpRouteOutcome;
};

/**
 * Persistent while the card is open rather than reverting on a timer: it
 * carries a command to read and paste, and for a deeplink it is the only
 * recovery when the OS hand-off silently did nothing.
 */
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
      <div className="flex items-start gap-1.5 text-green-600">
        <Check className="mt-0.5 size-3.5 shrink-0" />
        <span className="comet-body-xs leading-4">{route.confirmation}</span>
      </div>

      {route.snippet && (
        <div className="flex items-start gap-1.5 rounded border border-border bg-soft-background px-2 py-1">
          {/* break-words, not break-all: break-all split the command mid-token
              on this width ("c" / "laude-code"). */}
          <code className="min-w-0 flex-1 whitespace-pre-wrap break-words text-xs leading-4 text-muted-slate">
            {route.snippet}
          </code>
          <TooltipWrapper
            content={hasRecopied ? "Copied" : "Copy"}
            nonInteractive
          >
            <button
              type="button"
              aria-label="Copy the command"
              onClick={handleRecopy}
              className="mt-0.5 shrink-0 text-light-slate hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
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

      {route.note && (
        <p className="comet-body-xs leading-4 text-light-slate">{route.note}</p>
      )}
    </div>
  );
};

export default McpRouteConfirmation;
