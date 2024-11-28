import React from "react";
import {
  ColumnStatistic,
  DropdownOption,
  STATISTIC_AGGREGATION_TYPE,
} from "@/types/shared";
import get from "lodash/get";
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { ChevronDown } from "lucide-react";
import round from "lodash/round";

const ROUND_PRECISION = 3;

const OPTIONS: DropdownOption<string>[] = [
  {
    label: "Percentile 50",
    value: "p50",
  },
  {
    label: "Percentile 90",
    value: "p90",
  },
  {
    label: "Percentile 99",
    value: "p99",
  },
];

type HeaderStatisticProps = {
  statistic?: ColumnStatistic;
};

const HeaderStatistic: React.FC<HeaderStatisticProps> = ({ statistic }) => {
  const [value, setValue] = React.useState<string>("p50");
  switch (statistic?.type) {
    case STATISTIC_AGGREGATION_TYPE.AVG:
    case STATISTIC_AGGREGATION_TYPE.COUNT:
      return (
        <span className="comet-body-s truncate text-foreground	">
          <span>{statistic.type.toLowerCase()}</span>
          <span className="ml-1 font-semibold">
            {String(round(statistic.value, ROUND_PRECISION))}
          </span>
        </span>
      );
    case STATISTIC_AGGREGATION_TYPE.PERCENTAGE:
      return (
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <div className="flex max-w-full">
              <span className="comet-body-s truncate text-foreground	">
                <span>{value}</span>
                <span className="ml-1 font-semibold">
                  {String(
                    round(get(statistic.value, value, 0), ROUND_PRECISION),
                  )}
                </span>
              </span>
              <ChevronDown className="ml-0.5 size-3.5 shrink-0"></ChevronDown>
            </div>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end">
            {OPTIONS.map((option) => {
              return (
                <DropdownMenuCheckboxItem
                  key={option.value}
                  onSelect={() => setValue(option.value)}
                  checked={value === option.value}
                >
                  {option.label}
                </DropdownMenuCheckboxItem>
              );
            })}
          </DropdownMenuContent>
        </DropdownMenu>
      );
    default:
      return <span className="comet-body-s text-foreground">-</span>;
  }
};

export default HeaderStatistic;
