import React from "react";
import isUndefined from "lodash/isUndefined";
import { MoveRight, TrendingDown, TrendingUp } from "lucide-react";

import PercentageTrend, {
  PercentageTrendType,
} from "@/components/shared/PercentageTrend/PercentageTrend";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { Tag, TagProps } from "@/components/ui/tag";

type MetricComparisonCellProps = {
  baseline?: number;
  current?: number;
  formatter: (value: number) => string;
  trend?: PercentageTrendType;
  compact?: boolean;
};

const getTrendConfig = (percentage: number, trend: PercentageTrendType) => {
  if (Math.abs(percentage) < 0.5) {
    return { Icon: MoveRight, variant: "gray" as TagProps["variant"] };
  }
  if (percentage > 0) {
    return {
      Icon: TrendingUp,
      variant: (trend === "neutral"
        ? "gray"
        : trend === "direct"
          ? "green"
          : "red") as TagProps["variant"],
    };
  }
  return {
    Icon: TrendingDown,
    variant: (trend === "neutral"
      ? "gray"
      : trend === "direct"
        ? "red"
        : "green") as TagProps["variant"],
  };
};

const MetricComparisonCell: React.FunctionComponent<
  MetricComparisonCellProps
> = ({ baseline, current, formatter, trend = "direct", compact = false }) => {
  if (isUndefined(current) && isUndefined(baseline)) {
    return <span className="text-muted-slate">-</span>;
  }

  const percentage =
    !isUndefined(baseline) && !isUndefined(current) && baseline !== 0
      ? ((current - baseline) / Math.abs(baseline)) * 100
      : undefined;

  if (compact) {
    return (
      <div className="flex items-center gap-1.5">
        {!isUndefined(baseline) && (
          <TooltipWrapper content={String(baseline)}>
            <span className="comet-body-s text-muted-slate">
              {formatter(baseline)}
            </span>
          </TooltipWrapper>
        )}
        {!isUndefined(percentage) &&
          (() => {
            const { Icon, variant } = getTrendConfig(percentage, trend);
            return (
              <Tag
                size="sm"
                variant={variant}
                className="inline-flex items-center justify-center px-1"
              >
                <Icon className="size-3" />
              </Tag>
            );
          })()}
        {!isUndefined(current) ? (
          <TooltipWrapper content={String(current)}>
            <span className="comet-body-s-accented">{formatter(current)}</span>
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
