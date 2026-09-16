import React, { useCallback, useEffect, useRef, useState } from "react";
import { ArrowRight, Plug } from "lucide-react";

import { buildDocsUrl } from "@/lib/utils";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";
import InstallRoutes from "./InstallRoutes";
import McpRouteConfirmation from "./McpRouteConfirmation";
import useMcpInstallMode from "./useMcpInstallMode";
import { McpHintTarget, McpRouteOutcome } from "./types";
import {
  MCP_CONFIRMATION_DISMISS_MS,
  MCP_HINT_DESCRIPTION,
  MCP_HINT_DOCS_PATH,
  MCP_HINT_TITLE,
} from "./constants";

type McpHintPopoverProps = {
  /** Called when the user leaves through the card rather than abandoning it. */
  onAction: () => void;
  /** Whether a confirmation is on screen, which is what holds the card open. */
  onConfirmationChange: (isShowing: boolean) => void;
  /** Asked for when the hold expires and nobody is reading. */
  onDismiss: () => void;
  target: McpHintTarget;
};

const McpHintPopover: React.FunctionComponent<McpHintPopoverProps> = ({
  onAction,
  onConfirmationChange,
  onDismiss,
  target,
}) => {
  // Unmounted with the popover, so closing resets the view and a user who comes
  // back lands on the routes rather than a stale receipt.
  const [outcome, setOutcome] = useState<McpRouteOutcome | null>(null);
  const installMode = useMcpInstallMode();

  // Only a deeplink reports an outcome now: a copy says so where it was
  // clicked, in two seconds, like every other copy in the product.
  const handleRouteUsed = useCallback(
    (route: McpRouteOutcome) => {
      onAction();
      onConfirmationChange(true);
      setOutcome(route);
    },
    [onAction, onConfirmationChange],
  );

  // The fallback under a deeplink's confirmation copies the prompt, so it
  // belongs in the same funnel step as the prompt route itself.
  const handleRecopy = useCallback(() => {
    trackEvent(OpikEvent.MCP_HINT_PROMPT_COPIED, {
      install_mode: installMode,
      entity_type: target.entityType,
    });
  }, [installMode, target.entityType]);

  // A confirmation gets its moment and is then resolved one of two ways: the
  // card closes, or — if the pointer is on it, so somebody is reading — it goes
  // back to the routes. A timer, because it has to be cleared on unmount.
  //
  // It counts from the confirmation and nothing else. Keeping the callbacks in
  // the dependency list restarted the clock whenever one of them was rebuilt
  // higher up, which showed as three seconds taking four.
  const cardRef = useRef<HTMLDivElement>(null);
  const resolveRef = useRef<() => void>(() => {});
  resolveRef.current = () => {
    if (cardRef.current?.matches(":hover")) {
      setOutcome(null);
      onConfirmationChange(false);
      return;
    }
    onDismiss();
  };

  useEffect(() => {
    if (!outcome) return;

    const timer = setTimeout(
      () => resolveRef.current(),
      MCP_CONFIRMATION_DISMISS_MS,
    );

    return () => clearTimeout(timer);
  }, [outcome]);

  const handleLearnMoreClick = () => {
    onAction();
    trackEvent(OpikEvent.MCP_HINT_DOCS_CLICKED, {
      install_mode: installMode,
      entity_type: target.entityType,
    });
  };

  return (
    <div
      ref={cardRef}
      // Wide enough for the four tiles on one row, as the design has them. The
      // design's own 401px is against its type; ours needs the extra. The row
      // still wraps rather than overflowing if a narrow panel clamps the card.
      className="w-full rounded-md border bg-background p-1 font-mono shadow-lg"
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
            may have done nothing. A copy says so on the control that was
            clicked and leaves the card as it was. */}
        {outcome ? (
          <McpRouteConfirmation route={outcome} onRecopy={handleRecopy} />
        ) : (
          <>
            <p className="comet-body-xs mb-3 leading-4 text-muted-slate">
              {MCP_HINT_DESCRIPTION}
            </p>

            <InstallRoutes
              onRouteUsed={handleRouteUsed}
              onCopied={onAction}
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
