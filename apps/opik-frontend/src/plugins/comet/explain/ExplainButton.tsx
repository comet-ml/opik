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

// The expanded-pill gradient (Figma 351:31659): the resting -45deg gradient
// rotates to -18deg on hover/open. Colors are theme tokens from main.scss.
const PILL_GRADIENT =
  "bg-[linear-gradient(-18deg,var(--color-ollie-amber)_0%,var(--color-ollie)_100%)]";

// Owl trigger. A round icon button pinned to the cell's right edge (anchored by
// its LEFT, resting width ~26px incl. inset, so it grows RIGHT over the next
// cell). Hidden until the cell content is hovered; expands into the pill on its
// own hover, or stays expanded while the popover is `open`. z-10 keeps the grown
// pill above the neighbouring cell.
const owlTriggerClass = (open: boolean) =>
  cn(
    "group/explain absolute left-[calc(100%-26px)] top-1/2 z-10 flex -translate-y-1/2 items-center rounded-full border border-[var(--color-ollie)] p-1 text-white opacity-0 shadow-[var(--shadow-ollie)] transition-all",
    // Resting circular icon button (Figma 351:31623): -45deg gradient, symmetric p-1.
    "bg-[linear-gradient(-45deg,var(--color-ollie-amber)_0%,var(--color-ollie)_100%)]",
    // Hover → pill: gradient rotates to -18deg, right padding grows.
    `group-hover/explain:${PILL_GRADIENT} group-hover/explain:pr-1.5`,
    // Revealed when the cell content is hovered.
    "pointer-events-none group-hover/cell:pointer-events-auto group-hover/cell:opacity-100",
    // Kept visible + expanded while the popover is open.
    open && `pointer-events-auto ${PILL_GRADIENT} pr-1.5 opacity-100`,
  );

// The "Explain" label that slides in from zero width as the pill expands.
const owlLabelClass = (open: boolean) =>
  cn(
    "max-w-0 overflow-hidden whitespace-nowrap font-mono text-[10px] leading-3 opacity-0 transition-all duration-200 group-hover/explain:max-w-16 group-hover/explain:pl-1 group-hover/explain:opacity-100",
    open && "max-w-16 pl-1 opacity-100",
  );

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
  const handleOpenChange = (next: boolean) => {
    setOpen(next);
    if (next) handleOpen();
    else cancel(target);
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
          className={owlTriggerClass(open)}
        >
          <OllieOwl className="size-3 shrink-0" />
          <span className={owlLabelClass(open)}>Explain</span>
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
