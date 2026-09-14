import React, {
  useCallback,
  useEffect,
  useRef,
  type SyntheticEvent,
} from "react";

import { cn } from "@/lib/utils";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";
import { Popover, PopoverContent, PopoverTrigger } from "@/ui/popover";
import McpHintPopover from "./McpHintPopover";
import useHoverGrace from "./useHoverGrace";
import { MCP_HINT_LABEL } from "./constants";

// Keep clicks inside the popover from reaching the traceback underneath it.
const stop = (event: SyntheticEvent) => event.stopPropagation();

// The Ollie pill, same palette as the Explain affordance and the MCP
// announcement banner: amber into orange, with the Ollie shadow.
const PILL_CLASS = cn(
  "pointer-events-auto flex h-5 shrink-0 items-center rounded-full border px-1.5",
  "border-[var(--color-ollie)] text-white shadow-[var(--shadow-ollie)]",
  "bg-[linear-gradient(-45deg,var(--color-ollie-amber)_0%,var(--color-ollie)_100%)]",
  "transition-opacity hover:opacity-90",
  "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--color-ollie)] focus-visible:ring-offset-1",
);

const McpHintButton: React.FunctionComponent = () => {
  const { isOpen, open, closeNow, closeAfterGrace } = useHoverGrace();

  // Distinguishes "read it and walked away" from "used it". Only the first is
  // worth an event; the routes report themselves.
  const hasActedRef = useRef(false);
  const markAction = useCallback(() => {
    hasActedRef.current = true;
  }, []);

  // Radix restores focus to the trigger when the popover is dismissed, which
  // would re-open it through onFocus. Suppress exactly that one focus, and let
  // the next real one through.
  const ignoreNextFocusRef = useRef(false);

  // Driven off the resulting state rather than off each handler: hover, click
  // and keyboard all land here, and only the transition is an event.
  const wasOpenRef = useRef(false);
  useEffect(() => {
    if (isOpen === wasOpenRef.current) return;
    wasOpenRef.current = isOpen;

    if (isOpen) {
      hasActedRef.current = false;
      trackEvent(OpikEvent.MCP_POPOVER_OPENED);
      return;
    }
    if (!hasActedRef.current) {
      trackEvent(OpikEvent.MCP_POPOVER_CLOSED);
    }
  }, [isOpen]);

  const handleOpenChange = useCallback(
    (nextIsOpen: boolean) => {
      if (nextIsOpen) {
        open();
        return;
      }
      ignoreNextFocusRef.current = true;
      closeNow();
    },
    [open, closeNow],
  );

  const handleFocus = useCallback(() => {
    if (ignoreNextFocusRef.current) return;
    open();
  }, [open]);

  const handleBlur = useCallback(() => {
    ignoreNextFocusRef.current = false;
  }, []);

  return (
    <Popover open={isOpen} onOpenChange={handleOpenChange}>
      <PopoverTrigger asChild>
        <button
          type="button"
          className={PILL_CLASS}
          data-testid="mcp-hint-button"
          onPointerEnter={open}
          onPointerLeave={closeAfterGrace}
          onFocus={handleFocus}
          onBlur={handleBlur}
        >
          {/* Wrapped rather than bare so a page translator cannot re-parent the
              text node out from under React (see the browser-translation note
              in the frontend skill). */}
          <span className="whitespace-nowrap font-mono text-[10px] leading-3">
            {MCP_HINT_LABEL}
          </span>
        </button>
      </PopoverTrigger>
      <PopoverContent
        side="bottom"
        align="end"
        sideOffset={0}
        // The gap below the pill is padding on this transparent wrapper, not a
        // positional offset. An offset would leave a strip the pointer cannot
        // cross without the popover closing out from under it.
        className="w-auto border-0 bg-transparent p-0 pt-1.5 shadow-none"
        data-testid="mcp-hint-popover"
        // Hover must not steal focus; a keyboard user stays on the trigger.
        onOpenAutoFocus={(event) => event.preventDefault()}
        onPointerEnter={open}
        onPointerLeave={closeAfterGrace}
        onClick={stop}
        onPointerDown={stop}
      >
        <McpHintPopover onAction={markAction} />
      </PopoverContent>
    </Popover>
  );
};

export default McpHintButton;
