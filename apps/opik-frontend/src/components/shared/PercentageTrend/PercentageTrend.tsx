import React from "react";
import { MoveRight, TrendingDown, TrendingUp } from "lucide-react";
import isUndefined from "lodash/isUndefined";

import { formatNumericData } from "@/lib/utils";
import { Tag, TagProps } from "@/components/ui/tag";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

const getConfig = (
  percentage: number,
  trend: PercentageTrendType,
  precision: number,
) => {
  if (Math.abs(percentage) < Math.pow(10, -precision) / 2) {
    return {
      Icon: MoveRight,
      variant: "gray",
    };
  } else if (percentage > 0) {
    return {
      Icon: TrendingUp,
      variant:
        trend === "neutral" ? "gray" : trend === "direct" ? "green" : "red",
    };
  } else {
    return {
      Icon: TrendingDown,
      variant:
        trend === "neutral" ? "gray" : trend === "direct" ? "red" : "green",
    };
  }
};

export type PercentageTrendType = "direct" | "inverted" | "neutral";

type PercentageTrendProps = {
  percentage?: number;
  precision?: number;
  trend?: PercentageTrendType;
  tooltip?: string;
  iconOnly?: boolean;
};

const PercentageTrend: React.FC<PercentageTrendProps> = ({
  percentage,
  precision = 0,
  trend = "direct",
  tooltip,
  iconOnly = false,
}) => {
  if (isUndefined(percentage)) return null;

  const { Icon, variant } = getConfig(percentage, trend, precision);

  const isFinitePercentage = isFinite(percentage);

  const tag =
    iconOnly || !isFinitePercentage ? (
      <Tag
        size="sm"
        variant={variant as TagProps["variant"]}
        className="inline-flex items-center justify-center px-1"
      >
        <Icon className="size-3" />
      </Tag>
    ) : (
      <Tag
        size="md"
        variant={variant as TagProps["variant"]}
        className="flex-row flex-nowrap gap-1"
      >
        <div className="flex max-w-full items-center justify-between gap-0.5">
          <Icon className="size-3 shrink-0" />
          <div className="min-w-8 text-right">
            {formatNumericData(percentage, precision)}%
          </div>
        </div>
      </Tag>
    );

  if (tooltip) {
    return <TooltipWrapper content={tooltip}>{tag}</TooltipWrapper>;
  }

  return tag;
};

export default PercentageTrend;
