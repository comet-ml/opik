import React, { useState } from "react";
import { ArrowLeft, Check, Copy } from "lucide-react";

import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { McpInstallRoute } from "./types";

type McpRouteConfirmationProps = {
  route: McpInstallRoute;
  onBack: () => void;
};

/**
 * What the popover shows after a route is used.
 *
 * Persistent while the popover is open rather than reverting on a timer: it
 * carries a command the user has to read and paste, and for a deeplink it is
 * the only recovery there is — the OS hand-off cannot be observed from the
 * page, so a link that opened nothing looks exactly like one that worked.
 */
const McpRouteConfirmation: React.FunctionComponent<
  McpRouteConfirmationProps
> = ({ route, onBack }) => {
  const [hasRecopied, setHasRecopied] = useState(false);

  const handleRecopy = () => {
    if (!route.snippet) return;
    navigator.clipboard.writeText(route.snippet);
    setHasRecopied(true);
  };

  return (
    <div className="flex flex-col gap-2">
      <div className="flex items-center gap-1.5 text-primary">
        <Check className="size-3.5 shrink-0" />
        <span className="comet-body-xs leading-4">{route.confirmation}</span>
      </div>

      {route.snippet && (
        <div className="flex items-center gap-1.5 rounded border border-border bg-soft-background px-2 py-1">
          <code className="min-w-0 flex-1 truncate text-xs text-muted-slate">
            {route.snippet}
          </code>
          <TooltipWrapper content={hasRecopied ? "Copied" : "Copy"}>
            <button
              type="button"
              aria-label="Copy the command"
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

      {route.note && (
        <p className="comet-body-xs leading-4 text-light-slate">{route.note}</p>
      )}

      <button
        type="button"
        onClick={onBack}
        className="comet-body-xs inline-flex w-fit items-center gap-1 leading-4 text-muted-slate hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
      >
        <ArrowLeft className="size-3 shrink-0" />
        <span>Back</span>
      </button>
    </div>
  );
};

export default McpRouteConfirmation;
