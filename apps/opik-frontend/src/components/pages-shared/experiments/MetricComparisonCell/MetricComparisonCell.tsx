import React from "react";
import isUndefined from "lodash/isUndefined";

import { calcFormatterAwarePercentage } from "@/lib/percentage";
import PercentageTrend, {
  PercentageTrendType,
} from "@/components/shared/PercentageTrend/PercentageTrend";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

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
    return (
      <div className="flex items-center gap-1.5">
        {!isUndefined(baseline) && (
          <TooltipWrapper content={String(baseline)}>
            <span className="comet-body-s text-muted-slate">
              {formatter(baseline)}
            </span>
          </TooltipWrapper>
        )}
        <PercentageTrend percentage={percentage} trend={trend} iconOnly />
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
