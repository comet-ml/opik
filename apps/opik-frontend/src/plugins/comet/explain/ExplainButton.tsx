import { useState, type SyntheticEvent } from "react";
import { cn } from "@/lib/utils";
import { Popover, PopoverContent, PopoverTrigger } from "@/ui/popover";
import OllieOwl from "@/icons/ollie-owl.svg?react";
import { ExplainButtonProps } from "@/types/assistant-sidebar";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";
import useExplainStore, { useCanExplain } from "./explainStore";
import { getExplainConfig } from "./registry";
import ExplainPopover from "./ExplainPopover";

// Ollie brand gradient + shadow (Figma IconButton, node 351:31623).
const OLLIE_GRADIENT = "linear-gradient(-45deg, #F59E0B 0%, #F46E41 100%)";

// Stop pointer/click from reaching the table row (opening the trace panel).
const stop = (e: SyntheticEvent) => e.stopPropagation();

const ExplainButton = ({ target }: ExplainButtonProps) => {
  const canExplain = useCanExplain();
  const explain = useExplainStore((s) => s.explain);
  const config = getExplainConfig(target.kind);
  const [open, setOpen] = useState(false);

  // Fail-closed: pod not ready / no "explain" capability / unknown kind → no button.
  if (!canExplain || !config) return null;

  return (
    <Popover
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (next) {
          explain(target);
          trackEvent(OpikEvent.EXPLAIN_CLICKED, { kind: target.kind });
        }
      }}
    >
      <PopoverTrigger asChild>
        <button
          type="button"
          aria-label={config.label}
          style={{ backgroundImage: OLLIE_GRADIENT }}
          onClick={stop}
          onPointerDown={stop}
          onMouseDown={stop}
          className={cn(
            // Round owl button pinned to the cell's right edge, vertically
            // centered. Anchored by its LEFT (resting width ~26px incl. inset)
            // so it grows RIGHT over the next cell on hover. z-10 so the grown
            // pill sits above the neighbouring cell.
            "group/explain absolute left-[calc(100%-26px)] top-1/2 z-10 flex -translate-y-1/2 items-center rounded-full border border-[#F46E41] p-1 text-white opacity-0 shadow-[0px_4px_3px_rgba(244,110,65,0.1),0px_2px_2px_rgba(244,110,65,0.1)] transition-opacity",
            // Revealed on cell-content hover...
            "pointer-events-none group-hover/cell:pointer-events-auto group-hover/cell:opacity-100",
            // ...or kept visible while the popover is open (e.g. generating).
            open && "pointer-events-auto opacity-100",
          )}
        >
          <OllieOwl className="size-3 shrink-0" />
          <span
            className={cn(
              "max-w-0 overflow-hidden whitespace-nowrap font-mono text-[10px] leading-3 opacity-0 transition-all duration-200 group-hover/explain:max-w-16 group-hover/explain:pl-1 group-hover/explain:opacity-100",
              // Stay expanded while the popover is open.
              open && "max-w-16 pl-1 opacity-100",
            )}
          >
            Explain
          </span>
        </button>
      </PopoverTrigger>
      <PopoverContent
        side="right"
        align="start"
        className="w-72 px-1 py-2 font-mono text-xs shadow-[0px_4px_3px_rgba(0,0,0,0.1),0px_2px_2px_rgba(0,0,0,0.1)]"
        onClick={stop}
      >
        <ExplainPopover target={target} onContinue={() => setOpen(false)} />
      </PopoverContent>
    </Popover>
  );
};

export default ExplainButton;
