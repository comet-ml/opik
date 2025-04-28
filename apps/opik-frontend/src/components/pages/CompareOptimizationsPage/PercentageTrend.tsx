import React from "react";
import { MoveRight, TrendingDown, TrendingUp } from "lucide-react";
import isUndefined from "lodash/isUndefined";

import { Tag, TagProps } from "@/components/ui/tag";

const getConfig = (percentage: number) => {
  if (percentage === 0) {
    return {
      Icon: MoveRight,
      variant: "gray" as TagProps["variant"],
    };
  } else if (percentage > 0) {
    return {
      Icon: TrendingUp,
      variant: "green" as TagProps["variant"],
    };
  } else {
    return {
      Icon: TrendingDown,
      variant: "red" as TagProps["variant"],
    };
  }
};

type PercentageTrendProps = {
  percentage?: number;
  precision?: number;
};

const PercentageTrend: React.FC<PercentageTrendProps> = ({
  percentage,
  precision = 0,
}) => {
  if (isUndefined(percentage)) return null;

  const { Icon, variant } = getConfig(percentage);

  return (
    <Tag size="md" variant={variant} className="flex-row flex-nowrap gap-1">
      <div className="flex max-w-full items-center gap-0.5">
        <Icon className="size-3 shrink-0" />
        <span>{percentage.toFixed(precision)}%</span>
      </div>
    </Tag>
  );
};

export default PercentageTrend;
