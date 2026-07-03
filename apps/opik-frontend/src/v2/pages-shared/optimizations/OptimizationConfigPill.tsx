import React from "react";

import { Tag } from "@/ui/tag";
import { cn } from "@/lib/utils";

/**
 * Shared shell for the run-config header pills on the single-run overview.
 *
 * A white, bordered tag: 24px tall, 6px radius, 8px horizontal padding, with a
 * 12px muted-slate leading icon and 12px medium foreground text. Building every
 * config pill on top of the core `Tag` keeps the row visually uniform and gives
 * one place to tune the spec.
 */
export const CONFIG_PILL_CLASS =
  "comet-body-xs-accented flex h-6 items-center gap-1 rounded-md px-2 text-foreground";

/** Standard leading-icon styling for a config pill. */
export const CONFIG_PILL_ICON_CLASS = "size-3 shrink-0 text-muted-slate";

type OptimizationConfigPillProps = React.HTMLAttributes<HTMLDivElement> & {
  /** Leading icon, already rendered with `CONFIG_PILL_ICON_CLASS`. */
  icon: React.ReactNode;
  /** Optional trailing element (e.g. an expand affordance). */
  suffix?: React.ReactNode;
};

const OptimizationConfigPill = React.forwardRef<
  HTMLDivElement,
  OptimizationConfigPillProps
>(({ icon, suffix, children, className, ...props }, ref) => (
  <Tag
    ref={ref}
    variant="default"
    className={cn(CONFIG_PILL_CLASS, className)}
    {...props}
  >
    {icon}
    <span className="truncate">{children}</span>
    {suffix}
  </Tag>
));
OptimizationConfigPill.displayName = "OptimizationConfigPill";

export default OptimizationConfigPill;
