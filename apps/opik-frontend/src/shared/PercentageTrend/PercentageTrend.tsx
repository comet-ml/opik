import React from "react";
import { MoveRight, TrendingDown, TrendingUp } from "lucide-react";
import isUndefined from "lodash/isUndefined";

import { cn, formatNumericData } from "@/lib/utils";
import { Tag, TagProps } from "@/ui/tag";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

export type PercentageTrendType = "direct" | "inverted" | "neutral";
export type PercentageTrendVariant = "green" | "red" | "gray";

// Single source of truth for trend direction → icon + color variant. Exported so
// table cells (e.g. the runs-list metric pill) reuse the exact same mapping
// instead of re-deriving it.
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
};

// One trend style across the product (Figma): good = green, bad = red; neutral
// keeps the gray tag. Driven by the shared brand-color tokens so the trend and
// run-status colors stay in sync. Applied to the whole tag so the icon and the
// percentage share the color.
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
}) => {
  if (isUndefined(percentage)) return null;

  const { Icon, variant } = getTrendConfig(percentage, trend, precision);
  const colorClassName = TREND_COLOR_CLASS[variant];

  const isFinitePercentage = isFinite(percentage);

  const tag =
    iconOnly || !isFinitePercentage ? (
      <Tag
        size="sm"
        variant={variant as TagProps["variant"]}
        className={cn(
          "inline-flex items-center justify-center px-1",
          colorClassName,
        )}
      >
        <Icon className="size-3" />
      </Tag>
    ) : (
      <Tag
        size="md"
        variant={variant as TagProps["variant"]}
        className={cn("flex-row flex-nowrap gap-1", colorClassName)}
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
