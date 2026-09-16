import React, {
  useCallback,
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

// Click only, never pointerdown: stopping a pointerdown here also stops the
// native event Radix's dismissable layer listens for, which left its
// "last pointerdown was inside me" flag stuck on and cost an extra outside
// click to dismiss.
const stopPointerPropagation = (event: SyntheticEvent) =>
  event.stopPropagation();

// Same palette as the Explain affordance and the MCP announcement banner.
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

  // Distinguishes "read it and walked away" from "used it". The routes report
  // themselves, so only the first is worth an event.
  const hasActedRef = useRef(false);

  // Set for as long as a confirmation is on screen. It is shorter than the
  // routes it replaces, so the card used to shrink out from under the pointer
  // and the pointer-leave closed it before it could be read; while it shows,
  // only an explicit dismissal closes the card. A ref, not state: the dismissal
  // handlers clear it and Radix's own close has to see the new value within the
  // same event.
  const isPinnedRef = useRef(false);

  const markAction = useCallback(() => {
    hasActedRef.current = true;
  }, []);

  // Released once the card says the hold has served its purpose, or hover would
  // never get to close the card again.
  const handleConfirmationChange = useCallback((isShowing: boolean) => {
    isPinnedRef.current = isShowing;
  }, []);

  const contentRef = useRef<HTMLDivElement>(null);

  // Every way the card opens and closes goes through here, so the event is
  // reported where the change is made rather than derived from it afterwards.
  //
  // The guard reads a ref rather than the state it mirrors: a dismissal calls
  // this and Radix's own close follows in the same event, and state that has
  // not re-rendered yet would let both of them report the same close.
  const isOpenRef = useRef(false);
  const openCard = useCallback(
    (nextIsOpen: boolean) => {
      if (isOpenRef.current === nextIsOpen) return;
      isOpenRef.current = nextIsOpen;
      setIsOpen(nextIsOpen);

      if (nextIsOpen) {
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
    },
    [installMode, target.entityType],
  );

  // HoverCard covers pointer and focus. Click is ours, for touch.
  const handleClick = useCallback(() => openCard(true), [openCard]);

  // Two reasons to decline an implicit close: the pin above, and the trigger's
  // blur, which is what a keyboard user does on their way into the card.
  const handleOpenChange = useCallback(
    (nextIsOpen: boolean) => {
      if (!nextIsOpen) {
        if (isPinnedRef.current) return;
        if (contentRef.current?.contains(document.activeElement)) return;
      }
      openCard(nextIsOpen);
    },
    [openCard],
  );

  // Deliberate dismissals outrank both. Clearing the pin first lets Radix's own
  // close, which follows in the same event, through.
  const close = useCallback(() => {
    isPinnedRef.current = false;
    openCard(false);
  }, [openCard]);

  const handleContentBlur = useCallback(
    (event: React.FocusEvent<HTMLDivElement>) => {
      if (contentRef.current?.contains(event.relatedTarget)) return;
      // Using a deeplink hands off to another app, which takes the focus with
      // it. That is not the user dismissing what they just asked for.
      if (isPinnedRef.current) return;
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
          {/* Wrapped so a page translator cannot re-parent the text node. */}
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
        // No exit animation: Radix unmounts on `animationend`, which never
        // arrived here, so a closed card stayed on screen fully opaque. The `!`
        // is needed to beat the base variant's `animate-out`.
        className="w-[456px] border-0 bg-transparent p-0 shadow-none data-[state=closed]:!animate-none"
        data-testid="mcp-hint-popover"
        onPointerDownOutside={close}
        onEscapeKeyDown={close}
        onClick={stopPointerPropagation}
      >
        <McpHintPopover
          onAction={markAction}
          onConfirmationChange={handleConfirmationChange}
          onDismiss={close}
          target={target}
        />
      </HoverCardContent>
    </HoverCard>
  );
};

export default McpHintButton;
