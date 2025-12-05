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
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";

const PERCENTILE_OPTIONS: DropdownOption<string>[] = [
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

// Columns that should display percentiles alongside avg
const COLUMNS_WITH_PERCENTILES = [
  "total_estimated_cost",
  "usage.total_tokens",
];

// Check if statisticKey is a feedback score (starts with "feedback_scores.")
const isFeedbackScore = (key?: string) =>
  key?.startsWith("feedback_scores.") ?? false;

type HeaderStatisticProps = {
  statistic?: ColumnStatistic;
  percentileStatistic?: ColumnStatistic;
  columnsStatistic?: ColumnsStatistic;
  statisticKey?: string;
  dataFormater?: (value: number) => string;
};

const HeaderStatistic: React.FC<HeaderStatisticProps> = ({
  statistic,
  percentileStatistic,
  columnsStatistic,
  statisticKey,
  dataFormater = formatNumericData,
}) => {
  // Default to "avg" for AVG type, "p50" for PERCENTAGE type
  const defaultValue =
    statistic?.type === STATISTIC_AGGREGATION_TYPE.PERCENTAGE ? "p50" : "avg";
  const [selectedValue, setSelectedValue] =
    React.useState<string>(defaultValue);

  // Check if this column should display sum
  const shouldDisplaySum =
    statistic?.type === STATISTIC_AGGREGATION_TYPE.AVG &&
    statisticKey &&
    COLUMNS_WITH_SUM.includes(statisticKey);

  // Check if this column should display percentiles alongside avg
  const shouldDisplayPercentiles =
    percentileStatistic?.type === STATISTIC_AGGREGATION_TYPE.PERCENTAGE &&
    statisticKey &&
    (COLUMNS_WITH_PERCENTILES.includes(statisticKey) ||
      isFeedbackScore(statisticKey));

  // Calculate sum if needed: sum = count * avg
  let sumValue: number | null = null;
  if (shouldDisplaySum && columnsStatistic && statistic?.value) {
    const countStat = find(
      columnsStatistic,
      (s) =>
        s.type === STATISTIC_AGGREGATION_TYPE.COUNT &&
        (s.name === "trace_count" || s.name === "span_count"),
    );
    if (countStat) {
      sumValue = Number(countStat.value) * statistic.value;

      // Round token counts to whole numbers (tokens are discrete units)
      if (statisticKey && statisticKey.includes("tokens")) {
        sumValue = Math.round(sumValue);
      }
    }
  }

  // Get the display value based on selected option
  const getDisplayValue = (): number => {
    if (selectedValue === "avg" && statistic?.value !== undefined) {
      return statistic.value as number;
    }
    if (selectedValue === "sum" && sumValue !== null) {
      return sumValue;
    }
    if (
      ["p50", "p90", "p99"].includes(selectedValue) &&
      percentileStatistic?.type === STATISTIC_AGGREGATION_TYPE.PERCENTAGE
    ) {
      return get(percentileStatistic.value, selectedValue, 0);
    }
    return 0;
  };

  // Build dropdown options based on what's available
  const buildDropdownOptions = (): DropdownOption<string>[] => {
    const options: DropdownOption<string>[] = [];

    if (statistic?.type === STATISTIC_AGGREGATION_TYPE.AVG) {
      options.push({ label: "Average", value: "avg" });
    }

    if (shouldDisplaySum && sumValue !== null) {
      options.push({ label: "Sum", value: "sum" });
    }

    if (shouldDisplayPercentiles) {
      options.push(...PERCENTILE_OPTIONS);
    }

    return options;
  };

  switch (statistic?.type) {
    case STATISTIC_AGGREGATION_TYPE.AVG: {
      const dropdownOptions = buildDropdownOptions();

      // If we have more than just avg (i.e., sum and/or percentiles), show dropdown
      if (dropdownOptions.length > 1) {
        const displayValue = getDisplayValue();
        const showSeparator =
          shouldDisplayPercentiles &&
          (shouldDisplaySum || statistic?.type === STATISTIC_AGGREGATION_TYPE.AVG);

        return (
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <div className="flex max-w-full">
                <span className="comet-body-s truncate text-foreground">
                  <span>{selectedValue}</span>
                  <span className="ml-1 font-semibold">
                    {dataFormater(displayValue)}
                  </span>
                </span>
                <ChevronDown className="ml-0.5 size-3.5 shrink-0"></ChevronDown>
              </div>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              {dropdownOptions.map((option, index) => {
                // Add separator before percentile options if we have avg/sum
                const isFirstPercentile =
                  showSeparator && option.value === "p50";
                return (
                  <React.Fragment key={option.value}>
                    {isFirstPercentile && <DropdownMenuSeparator />}
                    <DropdownMenuCheckboxItem
                      onSelect={() => setSelectedValue(option.value)}
                      checked={selectedValue === option.value}
                    >
                      {option.label}
                    </DropdownMenuCheckboxItem>
                  </React.Fragment>
                );
              })}
            </DropdownMenuContent>
          </DropdownMenu>
        );
      }

      // Just avg, no dropdown needed
      return (
        <span className="comet-body-s truncate text-foreground">
          <span>avg</span>
          <span className="ml-1 font-semibold">
            {dataFormater(statistic.value)}
          </span>
        </span>
      );
    }
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
                <span>{selectedValue}</span>
                <span className="ml-1 font-semibold">
                  {dataFormater(get(statistic.value, selectedValue, 0))}
                </span>
              </span>
              <ChevronDown className="ml-0.5 size-3.5 shrink-0"></ChevronDown>
            </div>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end">
            {PERCENTILE_OPTIONS.map((option) => {
              return (
                <DropdownMenuCheckboxItem
                  key={option.value}
                  onSelect={() => setSelectedValue(option.value)}
                  checked={selectedValue === option.value}
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
