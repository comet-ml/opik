import React from "react";

import { Tag } from "@/ui/tag";
import { cn } from "@/lib/utils";

type StatusDotPillProps = {
  /** Dot fill; any CSS color value (hex or `var(--…)`). */
  dotColor: string;
  children: React.ReactNode;
  className?: string;
};

/**
 * The single "status dot + label" pill shared across the optimization tables
 * (the run list and the trials tab), so both always agree on shape.
 *
 * Spec: a neutral 20px pill, 8px horizontal padding, 6px dot gap, 12px
 * normal-weight label. `items-center` + `leading-none` keep the dot and label
 * vertically centered; `min-w-0` + a truncating label let it ellipsize inside a
 * narrow column instead of overflowing. Callers override the shell via
 * `className` (e.g. the two-tone best-trial treatment) and pick the dot colour.
 */
const StatusDotPill = React.forwardRef<HTMLDivElement, StatusDotPillProps>(
  ({ dotColor, children, className }, ref) => (
    <Tag
      ref={ref}
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
