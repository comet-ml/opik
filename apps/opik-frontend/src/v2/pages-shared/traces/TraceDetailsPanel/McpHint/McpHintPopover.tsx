import React, { useCallback, useEffect, useRef, useState } from "react";
import { ArrowRight, Plug } from "lucide-react";

import { buildDocsUrl } from "@/lib/utils";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";
import InstallRoutes from "./InstallRoutes";
import McpCopyConfirmation from "./McpCopyConfirmation";
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
  /** Whether a confirmation is on screen, which is what holds the card open. */
  onConfirmationChange: (isShowing: boolean) => void;
  /** Asked for when a copied confirmation has had its time and nobody is reading. */
  onDone: () => void;
  target: McpHintTarget;
};

const McpHintPopover: React.FunctionComponent<McpHintPopoverProps> = ({
  onAction,
  onConfirmationChange,
  onDone,
  target,
}) => {
  // Unmounted with the popover, so closing resets the view and a user who comes
  // back lands on the routes rather than a stale receipt.
  const [outcome, setOutcome] = useState<McpRouteOutcome | null>(null);
  const installMode = useMcpInstallMode();

  // When the last copy landed, and 0 for none. One signal for both ways a copy
  // can happen — a copy route, or the fallback under an opened deeplink — so
  // the clock below cannot start for one and not the other, and a second copy
  // restarts it rather than leaving the first deadline to close the card under
  // the user.
  const [copiedAt, setCopiedAt] = useState(0);

  const handleRouteUsed = useCallback(
    (route: McpRouteOutcome) => {
      onAction();
      onConfirmationChange(true);
      setOutcome(route);
      setCopiedAt(route.kind === "copied" ? Date.now() : 0);
    },
    [onAction, onConfirmationChange],
  );

  // The fallback under a confirmation copies the prompt, so it belongs in the
  // same funnel step as the prompt route itself.
  const handleRecopy = useCallback(() => {
    trackEvent(OpikEvent.MCP_PROMPT_COPIED, {
      install_mode: installMode,
      entity_type: target.entityType,
    });
    setCopiedAt(Date.now());
  }, [installMode, target.entityType]);

  // A copy is finished business, so the confirmation stands for a few seconds
  // and then the card gets out of the way — unless the pointer is still on it,
  // in which case it goes back to the routes rather than vanishing under them.
  //
  // A timer, not something an action can do on its own: it has to be cleared
  // when the card unmounts, and restarted rather than stacked when a second
  // copy lands. That cleanup is the whole reason this is an effect.
  const cardRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!copiedAt) return;

    const timer = setTimeout(() => {
      if (cardRef.current?.matches(":hover")) {
        setOutcome(null);
        setCopiedAt(0);
        onConfirmationChange(false);
        return;
      }
      onDone();
    }, MCP_COPIED_DISMISS_MS);

    return () => clearTimeout(timer);
  }, [copiedAt, onConfirmationChange, onDone]);

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
        {/* An opened deeplink takes the card over: it has nothing to do with
            the routes any more, and carries the fallback for a hand-off that
            may have done nothing. A copy only replaces the routes it came
            from. */}
        {outcome?.kind === "opened" ? (
          <McpRouteConfirmation route={outcome} onRecopy={handleRecopy} />
        ) : (
          <>
            <p className="comet-body-xs mb-3 leading-4 text-muted-slate">
              {MCP_HINT_DESCRIPTION}
            </p>

            {outcome ? (
              <McpCopyConfirmation confirmation={outcome.confirmation} />
            ) : (
              <InstallRoutes onRouteUsed={handleRouteUsed} target={target} />
            )}

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
