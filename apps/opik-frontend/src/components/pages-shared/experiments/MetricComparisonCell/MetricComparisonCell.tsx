import React from "react";
import isUndefined from "lodash/isUndefined";

import PercentageTrend, {
  PercentageTrendType,
} from "@/components/shared/PercentageTrend/PercentageTrend";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

type MetricComparisonCellProps = {
  baseline?: number;
  current?: number;
  formatter: (value: number) => string;
  trend?: PercentageTrendType;
};

const MetricComparisonCell: React.FunctionComponent<
  MetricComparisonCellProps
> = ({ baseline, current, formatter, trend = "direct" }) => {
  if (isUndefined(current) && isUndefined(baseline)) {
    return <span className="text-muted-slate">-</span>;
  }

  const percentage =
    !isUndefined(baseline) && !isUndefined(current) && baseline !== 0
      ? ((current - baseline) / Math.abs(baseline)) * 100
      : undefined;

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
