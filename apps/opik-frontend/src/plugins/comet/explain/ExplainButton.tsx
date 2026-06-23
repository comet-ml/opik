import { useState, type SyntheticEvent } from "react";
import { cn } from "@/lib/utils";
import { Popover, PopoverContent, PopoverTrigger } from "@/ui/popover";
import OllieOwl from "@/icons/ollie-owl.svg?react";
import { ExplainButtonProps } from "@/types/assistant-sidebar";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";
import useExplainStore, { cellKey, useCanExplain } from "./explainStore";
import { getExplainConfig } from "./registry";
import ExplainPopover from "./ExplainPopover";

// Stop pointer/click from reaching the table row (opening the trace panel).
const stop = (e: SyntheticEvent) => e.stopPropagation();

const ExplainButton = ({ target }: ExplainButtonProps) => {
  const canExplain = useCanExplain();
  const explain = useExplainStore((s) => s.explain);
  const cancel = useExplainStore((s) => s.cancel);
  const config = getExplainConfig(target.kind);
  const [open, setOpen] = useState(false);

  // Fail-closed: pod not ready / no "explain" capability / unknown kind → no button.
  if (!canExplain || !config) return null;

  // Opening dispatches the stream; only count a click that requests a *new*
  // explanation in BI. Checked before explain() mutates the store: reopening a
  // popover on a cached/in-flight cell reuses the entry (explain() still returns
  // true) and must not recount, and a throttled open returns false (shows a "try
  // again" message) and surfaces nothing — neither is a fresh explain.
  const handleOpen = () => {
    const cached = useExplainStore.getState().entries[cellKey(target)];
    const isFresh = !cached || cached.phase === "error";
    if (!explain(target)) return;
    if (isFresh) trackEvent(OpikEvent.EXPLAIN_CLICKED, { kind: target.kind });
  };

  // Closing mid-stream stops the (paid) generation the user won't see (no-op
  // for cached done/error cells). NB: a row unmounting while the popover is open
  // does NOT cancel (Radix doesn't fire onOpenChange on unmount) — the stream
  // completes to warm the cache for scroll-back, bounded by MAX_IN_FLIGHT.
  const handleClose = () => cancel(target);

  const handleOpenChange = (next: boolean) => {
    setOpen(next);
    if (next) handleOpen();
    else handleClose();
  };

  return (
    // `modal` matches the rest of the product's in-row menus/popovers (the cell
    // DropdownMenus are modal by default; FilterChipPopover et al. pass `modal`
    // explicitly). Without it the popover is non-modal, so the pointer-down that
    // dismisses it is NOT swallowed and lands on the table row underneath —
    // opening the trace/span/thread sidebar on the same click. Modal disables
    // outside pointer events while open, so the dismissing click only closes.
    <Popover open={open} onOpenChange={handleOpenChange} modal>
      <PopoverTrigger asChild>
        <button
          type="button"
          aria-label={config.label}
          onClick={stop}
          onPointerDown={stop}
          onMouseDown={stop}
          className={cn(
            // Round owl button pinned to the cell's right edge, vertically
            // centered. Anchored by its LEFT (resting width ~26px incl. inset)
            // so it grows RIGHT over the next cell on hover. z-10 so the grown
            // pill sits above the neighbouring cell.
            "group/explain absolute left-[calc(100%-26px)] top-1/2 z-10 flex -translate-y-1/2 items-center rounded-full border border-[var(--color-ollie)] p-1 text-white opacity-0 shadow-[var(--shadow-ollie)] transition-all",
            // Collapsed icon button (Figma 351:31623): -45deg gradient, p-1.
            "bg-[linear-gradient(-45deg,var(--color-ollie-amber)_0%,var(--color-ollie)_100%)]",
            // Expanded pill (Figma 351:31659): gradient rotates to -18deg and
            // right padding grows to pr-1.5. Resting state keeps symmetric p-1
            // so the icon-only button stays a circle.
            "group-hover/explain:bg-[linear-gradient(-18deg,var(--color-ollie-amber)_0%,var(--color-ollie)_100%)] group-hover/explain:pr-1.5",
            // Revealed on cell-content hover...
            "pointer-events-none group-hover/cell:pointer-events-auto group-hover/cell:opacity-100",
            // ...or kept visible + expanded while the popover is open.
            open &&
              "pointer-events-auto bg-[linear-gradient(-18deg,var(--color-ollie-amber)_0%,var(--color-ollie)_100%)] pr-1.5 opacity-100",
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
        className="w-72 px-1 py-2 font-mono text-xs"
        onClick={stop}
        // The owl trigger is only visible on cell hover / while open, so don't
        // hand focus back to it on close (it would be focused-but-invisible and
        // could scroll the row into view). Mirrors PromptLibraryMenu.
        onCloseAutoFocus={(e) => e.preventDefault()}
      >
        <ExplainPopover target={target} onContinue={() => setOpen(false)} />
      </PopoverContent>
    </Popover>
  );
};

export default ExplainButton;
