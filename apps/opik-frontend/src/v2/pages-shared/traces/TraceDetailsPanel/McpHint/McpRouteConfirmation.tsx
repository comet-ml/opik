import React, { useState } from "react";
import { Check, Copy } from "lucide-react";

import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { McpRouteOutcome } from "./types";

type McpRouteConfirmationProps = {
  route: McpRouteOutcome;
};

/**
 * What the card shows after a route is used.
 *
 * Persistent while the card is open rather than reverting on a timer: it
 * carries a command the user has to read and paste, and for a deeplink it is
 * the only recovery there is — the OS hand-off cannot be observed from the
 * page, so a link that opened nothing looks exactly like one that worked.
 */
const McpRouteConfirmation: React.FunctionComponent<
  McpRouteConfirmationProps
> = ({ route }) => {
  const [hasRecopied, setHasRecopied] = useState(false);

  const handleRecopy = () => {
    if (!route.snippet) return;
    navigator.clipboard.writeText(route.snippet);
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
          {/* break-words, not break-all: the card is narrow enough that
              break-all split the command mid-token, so "claude-code" wrapped as
              "c" / "laude-code". This wraps at the spaces and only breaks a
              token that cannot fit on a line of its own, such as a server URL. */}
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
