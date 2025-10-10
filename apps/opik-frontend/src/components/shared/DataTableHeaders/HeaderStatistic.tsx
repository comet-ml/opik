import React from "react";
import { ChevronDown } from "lucide-react";
import get from "lodash/get";
import find from "lodash/find";

import { formatNumericData } from "@/lib/utils";
import {
  ColumnStatistic,
  ColumnsStatistic,
  DropdownOption,
  STATISTIC_AGGREGATION_TYPE,
} from "@/types/shared";
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";

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

// Columns that should display sum alongside avg
const COLUMNS_WITH_SUM = [
  "total_estimated_cost",
  "usage.total_tokens",
  "usage.prompt_tokens",
  "usage.completion_tokens",
];

type HeaderStatisticProps = {
  statistic?: ColumnStatistic;
  columnsStatistic?: ColumnsStatistic;
  statisticKey?: string;
  dataFormater?: (value: number) => string;
};

const HeaderStatistic: React.FC<HeaderStatisticProps> = ({
  statistic,
  columnsStatistic,
  statisticKey,
  dataFormater = formatNumericData,
}) => {
  const [percentileValue, setPercentileValue] = React.useState<string>("p50");
  const [avgSumValue, setAvgSumValue] = React.useState<string>("avg");

  // Check if this column should display sum
  const shouldDisplaySum =
    statistic?.type === STATISTIC_AGGREGATION_TYPE.AVG &&
    statisticKey &&
    COLUMNS_WITH_SUM.includes(statisticKey);

  // Calculate sum if needed: sum = count * avg
  let sumValue: number | null = null;
  if (shouldDisplaySum && columnsStatistic) {
    const countStat = find(
      columnsStatistic,
      (s) =>
        s.type === STATISTIC_AGGREGATION_TYPE.COUNT &&
        (s.name === "trace_count" || s.name === "span_count"),
    );
    if (countStat && statistic?.value) {
      sumValue = Number(countStat.value) * statistic.value;

      // Round token counts to whole numbers (tokens are discrete units)
      if (statisticKey && statisticKey.includes("tokens")) {
        sumValue = Math.round(sumValue);
      }
    }
  }

  switch (statistic?.type) {
    case STATISTIC_AGGREGATION_TYPE.AVG:
      if (shouldDisplaySum && sumValue !== null) {
        const displayValue = avgSumValue === "avg" ? statistic.value : sumValue;
        return (
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <div className="flex max-w-full">
                <span className="comet-body-s truncate text-foreground">
                  <span>{avgSumValue}</span>
                  <span className="ml-1 font-semibold">
                    {dataFormater(displayValue)}
                  </span>
                </span>
                <ChevronDown className="ml-0.5 size-3.5 shrink-0"></ChevronDown>
              </div>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              <DropdownMenuCheckboxItem
                onSelect={() => setAvgSumValue("avg")}
                checked={avgSumValue === "avg"}
              >
                Average
              </DropdownMenuCheckboxItem>
              <DropdownMenuCheckboxItem
                onSelect={() => setAvgSumValue("sum")}
                checked={avgSumValue === "sum"}
              >
                Sum
              </DropdownMenuCheckboxItem>
            </DropdownMenuContent>
          </DropdownMenu>
        );
      }
      return (
        <span className="comet-body-s truncate text-foreground">
          <span>avg</span>
          <span className="ml-1 font-semibold">
            {dataFormater(statistic.value)}
          </span>
        </span>
      );
    case STATISTIC_AGGREGATION_TYPE.COUNT:
      return (
        <span className="comet-body-s truncate text-foreground">
          <span>{statistic.type.toLowerCase()}</span>
          <span className="ml-1 font-semibold">
            {dataFormater(statistic.value)}
          </span>
        </span>
      );
    case STATISTIC_AGGREGATION_TYPE.PERCENTAGE:
      return (
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <div className="flex max-w-full">
              <span className="comet-body-s truncate text-foreground">
                <span>{percentileValue}</span>
                <span className="ml-1 font-semibold">
                  {dataFormater(get(statistic.value, percentileValue, 0))}
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
                  onSelect={() => setPercentileValue(option.value)}
                  checked={percentileValue === option.value}
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
