import React from "react";
import isUndefined from "lodash/isUndefined";

import { cn, formatNumericData } from "@/lib/utils";
import {
  getTrendConfig,
  PercentageTrendType,
  TREND_COLOR_CLASS,
} from "@/shared/PercentageTrend/PercentageTrend";

type MetricTrendPillProps = {
  percentage?: number;
  trend?: PercentageTrendType;
  precision?: number;
};

/**
 * Neutral trend pill: a muted surface carrying a colored direction icon and an
 * optional percentage label. Shared by the optimization table and the trials
 * table so both render deltas identically. The icon shows for any percentage —
 * including Infinity from a zero baseline — and only the numeric label drops
 * when the value isn't finite.
 */
const MetricTrendPill: React.FunctionComponent<MetricTrendPillProps> = ({
  percentage,
  trend = "direct",
  precision = 0,
}) => {
  if (isUndefined(percentage)) return null;

  const { Icon, variant } = getTrendConfig(percentage, trend, precision);
  const label = isFinite(percentage)
    ? `${formatNumericData(percentage, precision)}%`
    : null;

  return (
    <div className="inline-flex h-5 items-center gap-1 rounded-md border border-[var(--pill-neutral-border)] bg-[var(--pill-neutral-bg)] px-1.5">
      <Icon className={cn("size-3 shrink-0", TREND_COLOR_CLASS[variant])} />
      {label && (
        <span className="comet-body-xs-accented text-muted-slate">{label}</span>
      )}
    </div>
  );
};

export default MetricTrendPill;
