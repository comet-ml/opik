import React from "react";
import { MoveRight, TrendingDown, TrendingUp } from "lucide-react";
import isUndefined from "lodash/isUndefined";

import { cn, formatNumericData } from "@/lib/utils";
import { Tag, TagProps } from "@/ui/tag";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

export type PercentageTrendType = "direct" | "inverted" | "neutral";
export type PercentageTrendVariant = "green" | "red" | "gray";

// Shared trend direction → icon + color mapping, reused by table cells.
export const getTrendConfig = (
  percentage: number,
  trend: PercentageTrendType,
  precision: number,
): { Icon: typeof MoveRight; variant: PercentageTrendVariant } => {
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

// Bright trend-icon colors for the metric pill (icon on a neutral surface).
// Not applied to the Tag below, which keeps its own variant text token.
export const TREND_COLOR_CLASS: Record<
  PercentageTrendVariant,
  string | undefined
> = {
  green: "text-[var(--color-green-bright)]",
  red: "text-[var(--color-red)]",
  gray: undefined,
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

  const { Icon, variant } = getTrendConfig(percentage, trend, precision);
  const tagVariant = variant as TagProps["variant"];

  const isIconOnly = iconOnly || !isFinite(percentage);
  const isCompact = size === "sm";

  const renderTag = () => {
    if (isIconOnly) {
      return (
        <Tag
          size={isCompact ? "default" : "sm"}
          variant={tagVariant}
          className={cn("inline-flex items-center justify-center px-1", {
            "rounded-md": isCompact,
          })}
        >
          <Icon className="size-3" />
        </Tag>
      );
    }

    if (isCompact) {
      return (
        <Tag
          size="default"
          variant={tagVariant}
          className="flex items-center gap-1 rounded-md px-1.5"
        >
          <Icon className="size-3 shrink-0" />
          <span>{formatNumericData(percentage, precision)}%</span>
        </Tag>
      );
    }

    return (
      <Tag
        size="md"
        variant={tagVariant}
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
  };

  const tag = renderTag();

  if (tooltip) {
    return <TooltipWrapper content={tooltip}>{tag}</TooltipWrapper>;
  }

  return tag;
};

export default PercentageTrend;
