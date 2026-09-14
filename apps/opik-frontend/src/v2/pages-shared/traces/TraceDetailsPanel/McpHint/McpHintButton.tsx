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
//
// Click only, never pointerdown: React's stopPropagation stops the native event
// at the root container, so a pointerdown stopped here never reaches the
// document listener Radix's dismissable layer uses to track whether the last
// pointerdown was inside it. That flag would then stay stuck on "inside", and
// the next click outside would be spent clearing it instead of dismissing —
// closing the card after using a route took two clicks.
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

  // Using a route swaps the routes out for a confirmation. That confirmation is
  // shorter than what it replaces, so the card shrinks out from under the
  // pointer that just clicked it, and the pointer-leave that follows closes the
  // card before it can be read. Once the user has committed to a route, hover
  // stops being what keeps the card alive — only an explicit dismissal closes
  // it (below). A ref rather than state: the dismissal handlers have to clear
  // it and have Radix's own close see the new value within the same event,
  // which a state update queued for the next render would not.
  const isPinnedRef = useRef(false);

  const markAction = useCallback(() => {
    hasActedRef.current = true;
    isPinnedRef.current = true;
  }, []);

  // Driven off the resulting state rather than off each handler: hover, click
  // and keyboard all land here, and only the transition is an event.
  const wasOpenRef = useRef(false);
  useEffect(() => {
    if (isOpen === wasOpenRef.current) return;
    wasOpenRef.current = isOpen;

    if (isOpen) {
      hasActedRef.current = false;
      isPinnedRef.current = false;
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

  // HoverCard covers pointer and keyboard focus on its own. Click is ours:
  // without it the card is unreachable on touch, where neither exists.
  const handleClick = useCallback(() => setIsOpen(true), []);

  const contentRef = useRef<HTMLDivElement>(null);

  // The two reasons an implicit close has to be declined. First, the pin above.
  // Second: HoverCard closes on the trigger's blur, which is exactly what a
  // keyboard user does on their way *into* the card — the tiles sit immediately
  // after the pill in tab order, so without this the card would close out from
  // under them a quarter-second after they arrive.
  const handleOpenChange = useCallback((nextIsOpen: boolean) => {
    if (!nextIsOpen) {
      if (isPinnedRef.current) return;
      if (contentRef.current?.contains(document.activeElement)) return;
    }
    setIsOpen(nextIsOpen);
  }, []);

  // Deliberate dismissals, which outrank both of those: clicking away, Escape,
  // and tabbing out. Each clears the pin first, so that Radix's own close —
  // which follows in the same event — is no longer declined. Without that,
  // dismissing a pinned card would take two clicks.
  const close = useCallback(() => {
    isPinnedRef.current = false;
    setIsOpen(false);
  }, []);

  const handleContentBlur = useCallback(
    (event: React.FocusEvent<HTMLDivElement>) => {
      if (contentRef.current?.contains(event.relatedTarget)) return;
      close();
    },
    [close],
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
        side="bottom"
        align="end"
        sideOffset={6}
        // No exit animation. Radix unmounts on `animationend`, and in this
        // position that event never arrives — the exit animation reports itself
        // as running forever, so the card stayed on screen, fully opaque, long
        // after it had closed, and clicking away looked like it did nothing.
        // Needs `!`: the base variant's `animate-out` otherwise wins on source
        // order. Entering still animates.
        className="w-auto border-0 bg-transparent p-0 shadow-none data-[state=closed]:!animate-none"
        data-testid="mcp-hint-popover"
        onPointerDownOutside={close}
        onEscapeKeyDown={close}
        onClick={stopPointerPropagation}
      >
        <McpHintPopover onAction={markAction} target={target} />
      </HoverCardContent>
    </HoverCard>
  );
};

export default McpHintButton;
