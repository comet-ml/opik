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

  // HoverCard covers pointer and keyboard focus on its own. Click is ours:
  // without it the card is unreachable on touch, where neither exists.
  const handleClick = useCallback(() => setIsOpen(true), []);

  return (
    <HoverCard
      open={isOpen}
      onOpenChange={setIsOpen}
      openDelay={0}
      closeDelay={MCP_HINT_CLOSE_DELAY_MS}
    >
      <HoverCardTrigger asChild>
        <button
          type="button"
          className={PILL_CLASS}
          data-testid="mcp-hint-button"
          aria-haspopup="dialog"
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
        side="bottom"
        align="end"
        sideOffset={6}
        className="w-auto border-0 bg-transparent p-0 shadow-none"
        data-testid="mcp-hint-popover"
        onClick={stopPointerPropagation}
        onPointerDown={stopPointerPropagation}
      >
        <McpHintPopover onAction={markAction} target={target} />
      </HoverCardContent>
    </HoverCard>
  );
};

export default McpHintButton;
