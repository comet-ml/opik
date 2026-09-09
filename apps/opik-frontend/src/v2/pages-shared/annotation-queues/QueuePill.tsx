import React from "react";
import { LucideIcon } from "lucide-react";

import { cn } from "@/lib/utils";

type QueuePillProps = {
  icon?: LucideIcon;
  className?: string;
  children: React.ReactNode;
};

/**
 * The neutral pill the annotation queue tables use for Scope, Automation and Source.
 *
 * <p>Surface taken from the Latency pill in Optimization runs, which the design names as the
 * reference: a 20px muted plate with a hairline border, a 12px icon in {@code --muted-gray} and an
 * accented label. It is deliberately not the {@code Tag} component — that one has no border, a 2px
 * radius and a different ground, so the two read as different objects side by side.
 */
const QueuePill: React.FC<QueuePillProps> = ({
  icon: Icon,
  className,
  children,
}) => (
  <div
    className={cn(
      "inline-flex h-5 items-center gap-1 rounded-md border border-[var(--pill-neutral-border)] bg-[var(--pill-neutral-bg)] px-1.5",
      className,
    )}
  >
    {Icon && <Icon className="size-3 shrink-0 text-muted-gray" />}
    <span className="comet-body-xs-accented truncate text-muted-slate">
      {children}
    </span>
  </div>
);

export default QueuePill;
