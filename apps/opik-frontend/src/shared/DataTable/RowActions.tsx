import React, { useCallback, useEffect, useRef, useState } from "react";
import { AnyRouter, Link, useRouter } from "@tanstack/react-router";
import { ArrowUpRight, LucideIcon, MoreHorizontal } from "lucide-react";
import { Button } from "@/ui/button";
import { Popover, PopoverAnchor, PopoverContent } from "@/ui/popover";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";
import { cn } from "@/lib/utils";

export type RowAction = {
  id: string;
  label: string;
  icon: LucideIcon;
  showLabel?: boolean;
  destructive?: boolean;
  disabled?: boolean;
  disabledReason?: string;
} & (
  | { onClick: () => void; link?: never }
  | { link: React.ComponentProps<typeof Link>; onClick?: never }
);

const OPEN_DELAY_MS = 100;
const CLOSE_DELAY_MS = 150;
// Width of the resting "..." plus its right padding (pr-2.5). The expanded
// panel overlaps it by this much, so its "..." lands on the resting one.
const ANCHOR_WIDTH = 34;

// Width of the right-edge gutter the actions column reserves (added to the last
// data column).
export const ROW_ACTIONS_GUTTER_SIZE = 44;

// The route's path pattern (no ids) names the table in analytics; `tab` tells
// apart tables that share a page, such as the Configuration tabs.
const getAnalyticsPage = (router: AnyRouter | null) => {
  if (!router) return {};
  const { matches, location } = router.state;
  const { tab } = location.search as { tab?: unknown };
  return {
    page: router.routesById[matches.at(-1)?.routeId ?? ""]?.fullPath,
    ...(typeof tab === "string" && { tab }),
  };
};

// Joins adjacent outline buttons into one segmented chip.
const GROUP_CLASSES =
  "flex items-stretch [&>*:not(:first-child)]:rounded-l-none [&>*:not(:first-child)]:border-l-0 [&>*:not(:last-child)]:rounded-r-none";

const ActionButton: React.FC<{
  action: RowAction;
  onDone: () => void;
  iconOnly?: boolean;
}> = ({ action, onDone, iconOnly }) => {
  const router = useRouter({ warn: false });
  const Icon = action.icon;
  const showLabel = action.showLabel && !iconOnly;
  const content = (
    <>
      <Icon />
      {showLabel && action.label}
      {showLabel && action.link?.target === "_blank" && <ArrowUpRight />}
    </>
  );

  const button = (
    <Button
      variant="outline"
      size={showLabel ? "2xs" : "icon-2xs"}
      aria-label={action.label}
      aria-disabled={action.disabled || undefined}
      asChild={Boolean(action.link)}
      className={cn(
        showLabel && "gap-1.5 px-2 [&>svg]:size-3.5 [&>svg]:shrink-0",
        action.disabled
          ? "cursor-not-allowed text-light-slate hover:bg-background hover:text-light-slate"
          : action.destructive && "text-destructive hover:text-destructive",
      )}
      onClick={(e) => {
        if (action.disabled) {
          e.preventDefault();
          return;
        }
        trackEvent(OpikEvent.ROW_ACTION_CLICKED, {
          ...getAnalyticsPage(router),
          action: action.id,
        });
        action.onClick?.();
        onDone();
      }}
    >
      {action.link ? <Link {...action.link}>{content}</Link> : content}
    </Button>
  );

  const tooltip = action.disabled
    ? (action.disabledReason ?? action.label)
    : !showLabel && action.label;

  return tooltip ? (
    <TooltipWrapper content={tooltip}>{button}</TooltipWrapper>
  ) : (
    button
  );
};

type RowActionsProps = {
  actions: RowAction[];
};

// Shown on row hover inside the table's right gutter (a 0px sticky column with
// size ROW_ACTIONS_GUTTER_SIZE and meta.overlay). A single action is shown
// directly; otherwise a "..." that, on hover or click, grows the chip leftwards
// with every action. That expanded chip is drawn in a portal exactly over the
// resting one, so column resize handles can't sit on top.
const RowActions: React.FC<RowActionsProps> = ({ actions }) => {
  const [open, setOpen] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout>>(undefined);

  useEffect(() => () => clearTimeout(timer.current), []);

  const scheduleOpen = (value: boolean) => {
    clearTimeout(timer.current);
    timer.current = setTimeout(
      () => setOpen(value),
      value ? OPEN_DELAY_MS : CLOSE_DELAY_MS,
    );
  };

  const animateExpand = useCallback((node: HTMLDivElement | null) => {
    node?.animate(
      [{ maxWidth: "0px" }, { maxWidth: `${node.scrollWidth}px` }],
      { duration: 150, easing: "ease-out" },
    );
  }, []);

  if (!actions.length) return null;

  const close = () => setOpen(false);
  const wrapperClassName =
    "absolute inset-y-0 right-0 flex items-center pr-2.5 opacity-0 focus-within:opacity-100 group-hover/row:opacity-100 data-[open=true]:opacity-100";

  if (actions.length === 1) {
    return (
      <div data-testid="row-actions" className={wrapperClassName}>
        <ActionButton action={actions[0]} onDone={() => {}} iconOnly />
      </div>
    );
  }

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverAnchor asChild>
        <div
          data-testid="row-actions"
          data-open={open}
          className={wrapperClassName}
        >
          <Button
            variant="outline"
            size="icon-2xs"
            aria-label="More actions"
            aria-expanded={open}
            onMouseEnter={() => scheduleOpen(true)}
            onMouseLeave={() => scheduleOpen(false)}
            onClick={() => {
              clearTimeout(timer.current);
              setOpen((v) => !v);
            }}
          >
            <MoreHorizontal />
          </Button>
        </div>
      </PopoverAnchor>
      <PopoverContent
        data-testid="row-actions-menu"
        side="left"
        align="center"
        sideOffset={-ANCHOR_WIDTH}
        avoidCollisions={false}
        className="flex h-[var(--radix-popper-anchor-height)] items-stretch rounded-none border-0 bg-transparent p-0 shadow-none data-[state=closed]:animate-none data-[state=open]:animate-none"
        onOpenAutoFocus={(e) => e.preventDefault()}
        onMouseEnter={() => clearTimeout(timer.current)}
        onMouseLeave={() => scheduleOpen(false)}
      >
        <div className="w-4 bg-gradient-to-r from-transparent to-row-selected" />
        <div className="flex items-center bg-row-selected pr-2.5">
          <div
            ref={animateExpand}
            className={cn(
              GROUP_CLASSES,
              "overflow-hidden rounded-l-md [&>*:last-child]:rounded-r-none",
            )}
          >
            {actions.map((action) => (
              <ActionButton key={action.id} action={action} onDone={close} />
            ))}
          </div>
          <Button
            variant="outline"
            size="icon-2xs"
            aria-label="Hide actions"
            className="rounded-l-none border-l-0"
            onClick={close}
          >
            <MoreHorizontal />
          </Button>
        </div>
      </PopoverContent>
    </Popover>
  );
};

export default RowActions;
