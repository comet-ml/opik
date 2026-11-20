import React from "react";

import { Tag } from "@/ui/tag";
import { cn } from "@/lib/utils";

type StatusDotPillProps = React.HTMLAttributes<HTMLDivElement> & {
  /** Dot fill; any CSS color value (hex or `var(--…)`). */
  dotColor: string;
  children: React.ReactNode;
};

/**
 * The single "status dot + label" pill shared across tables (the optimization
 * run list and trials tab, and experiment pass rates), so they agree on shape.
 *
 * Spec: a neutral 20px pill, 8px horizontal padding, 6px dot gap, 12px
 * normal-weight label. `items-center` + `leading-none` keep the dot and label
 * vertically centered; `min-w-0` + a truncating label let it ellipsize inside a
 * narrow column instead of overflowing. Callers override the shell via
 * `className` (e.g. the two-tone best-trial treatment) and pick the dot colour.
 * Other props are forwarded so a Radix `asChild` trigger (tooltips) can attach.
 */
const StatusDotPill = React.forwardRef<HTMLDivElement, StatusDotPillProps>(
  ({ dotColor, children, className, ...props }, ref) => (
    <Tag
      ref={ref}
      {...props}
      className={cn(
        "inline-flex h-5 min-w-0 items-center gap-1.5 border border-[var(--pill-neutral-border)] bg-[var(--pill-neutral-bg)] px-2 leading-none text-foreground",
        className,
      )}
    >
      <span
        className="size-1.5 shrink-0 rounded-full"
        style={{ backgroundColor: dotColor }}
      />
      <span className="truncate">{children}</span>
    </Tag>
  ),
);
StatusDotPill.displayName = "StatusDotPill";

export default StatusDotPill;
