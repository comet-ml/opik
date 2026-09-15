import React, { useCallback, useEffect, useRef, useState } from "react";
import { ArrowRight, Plug } from "lucide-react";

import { buildDocsUrl } from "@/lib/utils";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";
import InstallRoutes from "./InstallRoutes";
import McpRouteConfirmation from "./McpRouteConfirmation";
import useMcpInstallMode from "./useMcpInstallMode";
import { McpHintTarget, McpRouteOutcome } from "./types";
import {
  MCP_COPIED_DISMISS_MS,
  MCP_HINT_DESCRIPTION,
  MCP_HINT_DOCS_PATH,
  MCP_HINT_TITLE,
} from "./constants";

type McpHintPopoverProps = {
  /** Called when the user leaves through the card rather than abandoning it. */
  onAction: () => void;
  /** Asked for when a copied confirmation has had its time and nobody is reading. */
  onDone: () => void;
  target: McpHintTarget;
};

const McpHintPopover: React.FunctionComponent<McpHintPopoverProps> = ({
  onAction,
  onDone,
  target,
}) => {
  // Unmounted with the popover, so closing resets the view and a user who comes
  // back lands on the routes rather than a stale receipt.
  const [outcome, setOutcome] = useState<McpRouteOutcome | null>(null);
  // The prompt block's own confirmation, which stands in place of the block
  // rather than taking the card over.
  const [isCopied, setIsCopied] = useState(false);
  const installMode = useMcpInstallMode();

  const handleRouteUsed = useCallback(
    (route: McpRouteOutcome) => {
      onAction();
      setOutcome(route);
    },
    [onAction],
  );

  const handleCopied = useCallback(() => {
    onAction();
    setIsCopied(true);
  }, [onAction]);

  // A copy is finished business, so the confirmation stands for a few seconds
  // and then the card gets out of the way — unless the pointer is still on it,
  // in which case it goes back to the routes rather than vanishing under them.
  const cardRef = useRef<HTMLDivElement>(null);
  const hasCopied = isCopied || outcome?.kind === "copied";
  useEffect(() => {
    if (!hasCopied) return;

    const timer = setTimeout(() => {
      if (cardRef.current?.matches(":hover")) {
        setOutcome(null);
        setIsCopied(false);
        return;
      }
      onDone();
    }, MCP_COPIED_DISMISS_MS);

    return () => clearTimeout(timer);
  }, [hasCopied, onDone]);

  const handleLearnMoreClick = () => {
    onAction();
    trackEvent(OpikEvent.MCP_LEARN_MORE_CLICKED, {
      install_mode: installMode,
      entity_type: target.entityType,
    });
  };

  return (
    <div
      ref={cardRef}
      className="w-[279px] rounded-md border bg-background p-1 font-mono shadow-lg"
    >
      <div className="flex items-center gap-1.5 px-1.5 pb-1">
        <Plug className="size-4 shrink-0 text-[var(--color-ollie)]" />
        <span className="comet-body-xs leading-4 text-foreground">
          {MCP_HINT_TITLE}
        </span>
      </div>
      <div className="my-1 h-px w-full bg-border" />

      <div className="px-2 pb-1 pt-0.5">
        {outcome ? (
          <McpRouteConfirmation route={outcome} />
        ) : (
          <>
            <p className="comet-body-xs mb-3 leading-4 text-muted-slate">
              {MCP_HINT_DESCRIPTION}
            </p>

            <InstallRoutes
              onRouteUsed={handleRouteUsed}
              isCopied={isCopied}
              onCopied={handleCopied}
              target={target}
            />

            <a
              href={buildDocsUrl(MCP_HINT_DOCS_PATH)}
              target="_blank"
              rel="noreferrer"
              onClick={handleLearnMoreClick}
              className="comet-body-xs mt-3 inline-flex items-center gap-1 leading-4 text-foreground underline underline-offset-2 hover:text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            >
              <span>Learn more</span>
              <ArrowRight className="size-3 shrink-0" />
            </a>
          </>
        )}
      </div>
    </div>
  );
};

export default McpHintPopover;
