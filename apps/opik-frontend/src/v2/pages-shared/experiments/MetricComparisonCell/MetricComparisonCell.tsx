import React from "react";
import isUndefined from "lodash/isUndefined";

import { calcFormatterAwarePercentage } from "@/lib/percentage";
import PercentageTrend, {
  PercentageTrendType,
  getTrendConfig,
  TREND_COLOR_CLASS,
} from "@/shared/PercentageTrend/PercentageTrend";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { cn, formatNumericData } from "@/lib/utils";

type MetricComparisonCellProps = {
  baseline?: number;
  current?: number;
  formatter: (value: number) => string;
  trend?: PercentageTrendType;
  compact?: boolean;
};

const MetricComparisonCell: React.FunctionComponent<
  MetricComparisonCellProps
> = ({ baseline, current, formatter, trend = "direct", compact = false }) => {
  if (isUndefined(current) && isUndefined(baseline)) {
    return <span className="text-muted-slate">-</span>;
  }

  const percentage = calcFormatterAwarePercentage(current, baseline, formatter);

  if (compact) {
    // Show the trend icon for any percentage — including Infinity from a zero
    // baseline — and drop only the numeric label when the value isn't finite.
    const trendConfig = !isUndefined(percentage)
      ? getTrendConfig(percentage, trend, 0)
      : null;
    const TrendIcon = trendConfig?.Icon;
    const percentageLabel =
      !isUndefined(percentage) && isFinite(percentage)
        ? `${formatNumericData(percentage, 0)}%`
        : null;

    return (
      <div className="flex items-center gap-1.5">
        {trendConfig && TrendIcon && (
          <div className="inline-flex items-center gap-1 rounded-md border border-[var(--pill-neutral-border)] bg-[var(--pill-neutral-bg)] px-1.5 py-0.5">
            <TrendIcon
              className={cn(
                "size-3 shrink-0",
                TREND_COLOR_CLASS[trendConfig.variant],
              )}
            />
            {percentageLabel && (
              <span className="comet-body-xs-accented text-muted-slate">
                {percentageLabel}
              </span>
            )}
          </div>
        )}
        {!isUndefined(current) ? (
          <TooltipWrapper content={String(current)}>
            <span className="comet-body-xs text-foreground">
              {formatter(current)}
            </span>
          </TooltipWrapper>
        ) : (
          <span className="text-muted-slate">-</span>
        )}
      </div>
    );
  }

  return (
    <div className="flex items-center gap-2">
      <div className="flex items-center gap-1.5">
        {!isUndefined(baseline) && (
          <>
            <TooltipWrapper content={String(baseline)}>
              <span className="comet-body-s text-muted-slate">
                {formatter(baseline)}
              </span>
            </TooltipWrapper>
            <span className="text-muted-slate">→</span>
          </>
        )}
        {!isUndefined(current) ? (
          <TooltipWrapper content={String(current)}>
            <span className="comet-body-s-accented">{formatter(current)}</span>
          </TooltipWrapper>
        ) : (
          <span className="text-muted-slate">-</span>
        )}
      </div>
      {!isUndefined(percentage) && (
        <PercentageTrend percentage={percentage} trend={trend} />
      )}
    </div>
  );
};

export default MetricComparisonCell;
