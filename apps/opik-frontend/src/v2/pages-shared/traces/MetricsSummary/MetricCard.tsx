import React from "react";
import { ArrowUp, ArrowDown, LucideIcon } from "lucide-react";
import { cn } from "@/lib/utils";
import { PercentageTrendType } from "@/shared/PercentageTrend/PercentageTrend";

const computePercentageChange = (
  current: number | null,
  previous: number | null,
): number | undefined => {
  if (current === null && previous === null) return undefined;
  const c = current ?? 0;
  const p = previous ?? 0;
  if (p === 0) return c === 0 ? 0 : c > 0 ? Infinity : -Infinity;
  if (c === 0) return -100;
  return ((c - p) / p) * 100;
};

export type MetricCardProps = {
  icon: LucideIcon;
  label: string;
  value: string;
  currentRaw?: number | null;
  previousRaw?: number | null;
  trend: PercentageTrendType;
  selected?: boolean;
  onClick?: () => void;
  className?: string;
};

const MetricCard: React.FC<MetricCardProps> = ({
  icon: Icon,
  label,
  value,
  currentRaw,
  previousRaw,
  trend,
  selected = false,
  onClick,
  className,
}) => {
  const percentage = computePercentageChange(
    currentRaw ?? null,
    previousRaw ?? null,
  );

  const renderChange = () => {
    if (percentage === undefined || !isFinite(percentage)) return null;
    if (percentage === 0) {
      return <span className="text-xs text-light-slate">No changes</span>;
    }

    const isUp = percentage > 0;
    const isBetter = trend === "direct" ? isUp : !isUp;
    const ChangeIcon = isUp ? ArrowUp : ArrowDown;
    const colorClass = selected
      ? isBetter
        ? "text-primary"
        : "text-chart-red"
      : "text-muted-slate";
    return (
      <span
        className={cn(
          "inline-flex items-center gap-1 text-xs font-medium",
          colorClass,
        )}
      >
        <ChangeIcon className="size-3" />
        {`${Math.abs(percentage).toFixed(1)}%`}
      </span>
    );
  };

  return (
    <div
      className={cn(
        "flex h-11 cursor-pointer items-center justify-between border px-4 transition-colors [&:not(:last-child)]:-mr-px",
        selected ? "bg-background" : "bg-soft-background hover:bg-background",
        className,
      )}
      onClick={onClick}
    >
      <div className="flex items-center gap-3">
        <Icon className="size-4 shrink-0 text-muted-slate" />
        <div className="flex items-center gap-2">
          <span
            className={cn(
              "comet-body-s",
              selected ? "text-foreground" : "text-muted-slate",
            )}
          >
            {label}
          </span>
          <span
            className={cn(
              "comet-body-s",
              selected ? "text-foreground" : "text-muted-slate",
              value === "N/A" && "comet-body-xs text-light-slate",
            )}
          >
            {value}
          </span>
          {renderChange()}
        </div>
      </div>
    </div>
  );
};

export default MetricCard;
