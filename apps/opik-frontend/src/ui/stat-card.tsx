import * as React from "react";
import { ArrowRight, LucideIcon } from "lucide-react";

import { cn } from "@/lib/utils";
import { Card } from "@/ui/card";

/**
 * StatCard — a compound component for compact "statistic" cards: a titled
 * metric with an optional trend badge, a primary value, and a supporting
 * baseline→current delta or caption.
 *
 * Compose the parts to build any of the Optimization Runs card variants (and
 * more):
 *
 *   <StatCard>
 *     <StatCard.Header>
 *       <StatCard.Title icon={Scale}>geval</StatCard.Title>
 *       <PercentageTrend percentage={33} />
 *     </StatCard.Header>
 *     <StatCard.Value>100%</StatCard.Value>
 *     <StatCard.Delta from="75%" to="100%" />
 *   </StatCard>
 */
const StatCardRoot = React.forwardRef<
  HTMLDivElement,
  React.HTMLAttributes<HTMLDivElement>
>(({ className, ...props }, ref) => (
  <Card
    ref={ref}
    className={cn("flex flex-col px-3 py-2", className)}
    {...props}
  />
));
StatCardRoot.displayName = "StatCard";

/**
 * Top row: title (icon + label) on the left, optional trailing badge right.
 * Fixed height (h-7 = 20px content row + pb-2) keeps the value below aligned
 * across cards whether or not a trailing badge is present.
 */
const StatCardHeader = React.forwardRef<
  HTMLDivElement,
  React.HTMLAttributes<HTMLDivElement>
>(({ className, ...props }, ref) => (
  <div
    ref={ref}
    className={cn("flex h-7 w-full items-center gap-1 pb-2", className)}
    {...props}
  />
));
StatCardHeader.displayName = "StatCard.Header";

interface StatCardTitleProps extends React.HTMLAttributes<HTMLDivElement> {
  icon?: LucideIcon;
}

/** Leading icon + label; grows to push trailing header content to the right. */
const StatCardTitle = React.forwardRef<HTMLDivElement, StatCardTitleProps>(
  ({ className, icon: Icon, children, ...props }, ref) => (
    <div
      ref={ref}
      className={cn("flex min-w-0 flex-1 items-center gap-1", className)}
      {...props}
    >
      {Icon ? <Icon className="size-3 shrink-0 text-muted-slate" /> : null}
      <span className="comet-body-xs truncate text-muted-slate">
        {children}
      </span>
    </div>
  ),
);
StatCardTitle.displayName = "StatCard.Title";

/** Primary metric value. */
const StatCardValue = React.forwardRef<
  HTMLDivElement,
  React.HTMLAttributes<HTMLDivElement>
>(({ className, ...props }, ref) => (
  <div
    ref={ref}
    className={cn("comet-body-accented truncate text-foreground", className)}
    {...props}
  />
));
StatCardValue.displayName = "StatCard.Value";

interface StatCardDeltaProps
  extends Omit<React.HTMLAttributes<HTMLDivElement>, "children"> {
  from: React.ReactNode;
  to: React.ReactNode;
}

/** Baseline → current comparison row. */
const StatCardDelta = React.forwardRef<HTMLDivElement, StatCardDeltaProps>(
  ({ className, from, to, ...props }, ref) => (
    <div
      ref={ref}
      className={cn("flex items-center gap-1 text-[10px] leading-4", className)}
      {...props}
    >
      <span className="truncate text-light-slate">{from}</span>
      <ArrowRight className="size-2.5 shrink-0 text-light-slate" />
      <span className="truncate text-muted-slate">{to}</span>
    </div>
  ),
);
StatCardDelta.displayName = "StatCard.Delta";

/** Single supporting line beneath the value (e.g. "4m 25s total"). */
const StatCardCaption = React.forwardRef<
  HTMLDivElement,
  React.HTMLAttributes<HTMLDivElement>
>(({ className, ...props }, ref) => (
  <div
    ref={ref}
    className={cn("truncate text-[10px] leading-4 text-light-slate", className)}
    {...props}
  />
));
StatCardCaption.displayName = "StatCard.Caption";

const StatCard = Object.assign(StatCardRoot, {
  Header: StatCardHeader,
  Title: StatCardTitle,
  Value: StatCardValue,
  Delta: StatCardDelta,
  Caption: StatCardCaption,
});

export { StatCard };
