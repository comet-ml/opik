import React, {
  useCallback,
  useEffect,
  useRef,
  useState,
  type SyntheticEvent,
} from "react";

import { cn } from "@/lib/utils";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";
import { HoverCard, HoverCardContent, HoverCardTrigger } from "@/ui/hover-card";
import McpHintPopover from "./McpHintPopover";
import useMcpInstallMode from "./useMcpInstallMode";
import { MCP_HINT_CLOSE_DELAY_MS, MCP_HINT_LABEL } from "./constants";
import { McpHintTarget } from "./types";

// Keep clicks inside the card from reaching the traceback underneath it.
const stopPointerPropagation = (event: SyntheticEvent) =>
  event.stopPropagation();

// The Ollie pill, same palette as the Explain affordance and the MCP
// announcement banner: amber into orange, with the Ollie shadow.
const PILL_CLASS = cn(
  "pointer-events-auto flex h-5 shrink-0 items-center rounded-full border px-1.5",
  "border-[var(--color-ollie)] text-white shadow-[var(--shadow-ollie)]",
  "bg-[linear-gradient(-45deg,var(--color-ollie-amber)_0%,var(--color-ollie)_100%)]",
  "transition-opacity hover:opacity-90",
  "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--color-ollie)] focus-visible:ring-offset-1",
);

type McpHintButtonProps = {
  target: McpHintTarget;
};

const McpHintButton: React.FunctionComponent<McpHintButtonProps> = ({
  target,
}) => {
  const [isOpen, setIsOpen] = useState(false);
  const installMode = useMcpInstallMode();

  // Distinguishes "read it and walked away" from "used it". Only the first is
  // worth an event; the routes report themselves.
  const hasActedRef = useRef(false);
  const markAction = useCallback(() => {
    hasActedRef.current = true;
  }, []);

  // Driven off the resulting state rather than off each handler: hover, click
  // and keyboard all land here, and only the transition is an event.
  const wasOpenRef = useRef(false);
  useEffect(() => {
    if (isOpen === wasOpenRef.current) return;
    wasOpenRef.current = isOpen;

    if (isOpen) {
      hasActedRef.current = false;
      trackEvent(OpikEvent.MCP_POPOVER_OPENED, {
        install_mode: installMode,
        entity_type: target.entityType,
      });
      return;
    }
    if (!hasActedRef.current) {
      trackEvent(OpikEvent.MCP_POPOVER_CLOSED, {
        install_mode: installMode,
        entity_type: target.entityType,
      });
    }
  }, [isOpen, installMode, target.entityType]);

  // A confirmation is showing. It is shorter than the route list, so the card
  // shrinks out from under the pointer that just clicked — and the pointer-leave
  // that follows would close it before the user has read what it says.
  const [hasOutcome, setHasOutcome] = useState(false);

  const triggerRef = useRef<HTMLButtonElement>(null);
  const contentRef = useRef<HTMLDivElement>(null);
  const holdsFocus = () =>
    Boolean(contentRef.current?.contains(document.activeElement));

  // HoverCard closes on the trigger's blur — which is exactly what a keyboard
  // user does on their way *into* the card. The tiles sit immediately after the
  // pill in tab order, so without this the card closes out from under them a
  // quarter-second after they arrive. Decline while it holds focus.
  const close = useCallback(() => {
    setHasOutcome(false);
    setIsOpen(false);
  }, []);

  // HoverCard covers pointer and keyboard focus on its own. Click is ours, and
  // it toggles: a control that opens on click but cannot close again makes the
  // user go looking for somewhere else to click. Also the only way in on touch,
  // where neither hover nor focus exists.
  const handleClick = useCallback(() => {
    if (isOpen) {
      close();
      return;
    }
    setIsOpen(true);
  }, [isOpen, close]);

  // The trigger sits outside the content, so its own pointer-down counts as an
  // outside interaction. Left alone it would close the card a moment before the
  // click reopened it, and the toggle would never appear to work.
  const handlePointerDownOutside = useCallback(
    (event: Event) => {
      if (triggerRef.current?.contains(event.target as Node)) return;
      close();
    },
    [close],
  );

  const handleOpenChange = useCallback(
    (nextIsOpen: boolean) => {
      // Decline the pointer's verdict while the card holds focus or is showing
      // a confirmation. Escape and a click outside still close it, below.
      if (!nextIsOpen && (holdsFocus() || hasOutcome)) return;
      setIsOpen(nextIsOpen);
    },
    [hasOutcome],
  );

  // ...and close once focus actually leaves it, rather than waiting for a
  // pointer that a keyboard user never moves.
  const handleContentBlur = useCallback(
    (event: React.FocusEvent<HTMLDivElement>) => {
      if (contentRef.current?.contains(event.relatedTarget)) return;
      if (hasOutcome) return;
      setIsOpen(false);
    },
    [hasOutcome],
  );

  return (
    <HoverCard
      open={isOpen}
      onOpenChange={handleOpenChange}
      openDelay={0}
      closeDelay={MCP_HINT_CLOSE_DELAY_MS}
    >
      <HoverCardTrigger asChild>
        <button
          ref={triggerRef}
          type="button"
          className={PILL_CLASS}
          data-testid="mcp-hint-button"
          aria-expanded={isOpen}
          onClick={handleClick}
        >
          {/* Wrapped rather than bare so a page translator cannot re-parent the
              text node out from under React (see the browser-translation note
              in the frontend skill). */}
          <span className="whitespace-nowrap font-mono text-[10px] leading-3">
            {MCP_HINT_LABEL}
          </span>
        </button>
      </HoverCardTrigger>
      <HoverCardContent
        ref={contentRef}
        onBlur={handleContentBlur}
        onEscapeKeyDown={close}
        onPointerDownOutside={handlePointerDownOutside}
        onFocusOutside={close}
        side="bottom"
        align="end"
        sideOffset={6}
        // No exit animation. Radix unmounts the card on `animationend`, and in
        // this position that event never arrives — the exit animation reports
        // itself as running forever, so the card stayed on screen, fully
        // opaque, long after it had closed. Clicking away appeared to do
        // nothing at all. Entering still animates.
        className="w-auto border-0 bg-transparent p-0 shadow-none data-[state=closed]:!animate-none"
        data-testid="mcp-hint-popover"
        onClick={stopPointerPropagation}
        onPointerDown={stopPointerPropagation}
      >
        <McpHintPopover
          onAction={markAction}
          onOutcomeChange={setHasOutcome}
          target={target}
        />
      </HoverCardContent>
    </HoverCard>
  );
};

export default McpHintButton;
