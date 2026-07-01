import React from "react";
import { MoveRight, TrendingDown, TrendingUp } from "lucide-react";
import isUndefined from "lodash/isUndefined";

import { cn, formatNumericData } from "@/lib/utils";
import { Tag, TagProps } from "@/ui/tag";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

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
  /**
   * "md" (default) is the standard tag used across tables and overviews.
   * "sm" is the compact 20px pill used inside stat cards.
   */
  size?: "sm" | "md";
};

const PercentageTrend: React.FC<PercentageTrendProps> = ({
  percentage,
  precision = 0,
  trend = "direct",
  tooltip,
  iconOnly = false,
  size = "md",
}) => {
  if (isUndefined(percentage)) return null;

  const { Icon, variant } = getConfig(percentage, trend, precision);

  const isFinitePercentage = isFinite(percentage);
  const isIconOnly = iconOnly || !isFinitePercentage;
  const isCompact = size === "sm";

  let tag: React.ReactNode;

  if (isIconOnly) {
    tag = (
      <Tag
        size={isCompact ? "default" : "sm"}
        variant={variant as TagProps["variant"]}
        className={cn("inline-flex items-center justify-center px-1", {
          "rounded-md": isCompact,
        })}
      >
        <Icon className="size-3" />
      </Tag>
    );
  } else if (isCompact) {
    tag = (
      <Tag
        size="default"
        variant={variant as TagProps["variant"]}
        className="flex items-center gap-1 rounded-md px-1.5"
      >
        <Icon className="size-3 shrink-0" />
        <span>{formatNumericData(percentage, precision)}%</span>
      </Tag>
    );
  } else {
    tag = (
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
  }

  if (tooltip) {
    return <TooltipWrapper content={tooltip}>{tag}</TooltipWrapper>;
  }

  return tag;
};

export default PercentageTrend;
