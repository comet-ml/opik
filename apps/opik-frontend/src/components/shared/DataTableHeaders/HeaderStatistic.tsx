import React from "react";
import { ChevronDown } from "lucide-react";
import get from "lodash/get";
import find from "lodash/find";

import { formatNumericData } from "@/lib/utils";
import {
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
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

// Aggregation value constants
export const AGGREGATION_VALUE = {
  AVG: "avg",
  SUM: "sum",
  P50: "p50",
  P90: "p90",
  P99: "p99",
} as const;

type AggregationValue =
  (typeof AGGREGATION_VALUE)[keyof typeof AGGREGATION_VALUE];

const PERCENTILE_VALUES = [
  AGGREGATION_VALUE.P50,
  AGGREGATION_VALUE.P90,
  AGGREGATION_VALUE.P99,
] as const;

const PERCENTILE_OPTIONS: DropdownOption<string>[] = [
  {
    label: "Percentile 50",
    value: AGGREGATION_VALUE.P50,
  },
  {
    label: "Percentile 90",
    value: AGGREGATION_VALUE.P90,
  },
  {
    label: "Percentile 99",
    value: AGGREGATION_VALUE.P99,
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
  columnsStatistic?: ColumnsStatistic;
  statisticKey?: string;
  dataFormater?: (value: number) => string;
  tooltipFormater?: (value: number) => string;
  supportsPercentiles?: boolean;
};

const HeaderStatistic: React.FC<HeaderStatisticProps> = ({
  columnsStatistic,
  statisticKey,
  dataFormater = formatNumericData,
  tooltipFormater,
  supportsPercentiles = false,
}) => {
  const formatTooltip = (value: number) =>
    tooltipFormater ? tooltipFormater(value) : String(value);

  // Find all statistics matching the key (could have both AVG and PERCENTAGE)
  const matchingStatistics = React.useMemo(() => {
    if (!columnsStatistic || !statisticKey) return [];
    return columnsStatistic.filter((s) => s.name === statisticKey);
  }, [columnsStatistic, statisticKey]);

  // Find AVG and PERCENTAGE statistics separately
  const statistic = React.useMemo(
    () =>
      matchingStatistics.find(
        (s) => s.type === STATISTIC_AGGREGATION_TYPE.AVG,
      ) ?? matchingStatistics[0],
    [matchingStatistics],
  );

  const percentileStatistic = React.useMemo(
    () =>
      matchingStatistics.find(
        (s) => s.type === STATISTIC_AGGREGATION_TYPE.PERCENTAGE,
      ),
    [matchingStatistics],
  );

  // Default to "avg" for AVG type, "p50" for PERCENTAGE type
  const defaultValue =
    statistic?.type === STATISTIC_AGGREGATION_TYPE.PERCENTAGE
      ? AGGREGATION_VALUE.P50
      : AGGREGATION_VALUE.AVG;
  const [selectedValue, setSelectedValue] =
    React.useState<AggregationValue>(defaultValue);

  // Check if this column should display sum
  const shouldDisplaySum =
    statistic?.type === STATISTIC_AGGREGATION_TYPE.AVG &&
    statisticKey &&
    COLUMNS_WITH_SUM.includes(statisticKey);

  // Check if this column should display percentiles alongside avg
  const shouldDisplayPercentiles =
    supportsPercentiles &&
    percentileStatistic?.type === STATISTIC_AGGREGATION_TYPE.PERCENTAGE;

  // Get sum value from backend stats
  let sumValue: number | null = null;
  if (shouldDisplaySum && columnsStatistic) {
    // Map column key to its corresponding sum stat name
    const sumStatName = statisticKey.startsWith("usage.")
      ? statisticKey.replace("usage.", "usage_sum.")
      : statisticKey === "total_estimated_cost"
        ? "total_estimated_cost_sum"
        : null;

    if (sumStatName) {
      const sumStat = find(
        columnsStatistic,
        (s) =>
          s.name === sumStatName && s.type === STATISTIC_AGGREGATION_TYPE.AVG,
      );
      if (sumStat) {
        sumValue = Number(sumStat.value);

        if (statisticKey.includes("tokens")) {
          sumValue = Math.round(sumValue);
        }
      }
    }
  }

  // Get the display value based on selected option
  const getDisplayValue = (): number => {
    if (
      selectedValue === AGGREGATION_VALUE.AVG &&
      statistic?.value !== undefined
    ) {
      return statistic.value as number;
    }
    if (selectedValue === AGGREGATION_VALUE.SUM && sumValue !== null) {
      return sumValue;
    }
    if (
      PERCENTILE_VALUES.includes(
        selectedValue as (typeof PERCENTILE_VALUES)[number],
      ) &&
      percentileStatistic?.type === STATISTIC_AGGREGATION_TYPE.PERCENTAGE
    ) {
      return get(percentileStatistic.value, selectedValue, 0);
    }
    return 0;
  };

  // Build dropdown options for avg/sum only
  const buildAvgSumOptions = (): DropdownOption<string>[] => {
    const options: DropdownOption<string>[] = [];

    if (statistic?.type === STATISTIC_AGGREGATION_TYPE.AVG) {
      options.push({ label: "Average", value: AGGREGATION_VALUE.AVG });
    }

    if (shouldDisplaySum && sumValue !== null) {
      options.push({ label: "Sum", value: AGGREGATION_VALUE.SUM });
    }

    return options;
  };

  switch (statistic?.type) {
    case STATISTIC_AGGREGATION_TYPE.AVG: {
      const avgSumOptions = buildAvgSumOptions();
      const hasMultipleOptions =
        avgSumOptions.length > 1 || shouldDisplayPercentiles;

      // If we have more than just avg (i.e., sum and/or percentiles), show dropdown
      if (hasMultipleOptions) {
        const displayValue = getDisplayValue();

        return (
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <div className="flex max-w-full">
                <span className="comet-body-s truncate text-foreground">
                  <span>{selectedValue}</span>
                  <TooltipWrapper content={formatTooltip(displayValue)}>
                    <span className="ml-1 font-semibold">
                      {dataFormater(displayValue)}
                    </span>
                  </TooltipWrapper>
                </span>
                <ChevronDown className="ml-0.5 size-3.5 shrink-0"></ChevronDown>
              </div>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              {/* Render avg/sum options */}
              {avgSumOptions.map((option) => (
                <DropdownMenuCheckboxItem
                  key={option.value}
                  onSelect={() =>
                    setSelectedValue(option.value as AggregationValue)
                  }
                  checked={selectedValue === option.value}
                >
                  {option.label}
                </DropdownMenuCheckboxItem>
              ))}

              {/* Add separator between avg/sum and percentiles if both exist */}
              {avgSumOptions.length > 0 && shouldDisplayPercentiles && (
                <DropdownMenuSeparator />
              )}

              {/* Render percentile options */}
              {shouldDisplayPercentiles &&
                PERCENTILE_OPTIONS.map((option) => (
                  <DropdownMenuCheckboxItem
                    key={option.value}
                    onSelect={() =>
                      setSelectedValue(option.value as AggregationValue)
                    }
                    checked={selectedValue === option.value}
                  >
                    {option.label}
                  </DropdownMenuCheckboxItem>
                ))}
            </DropdownMenuContent>
          </DropdownMenu>
        );
      }

      // Just avg, no dropdown needed
      return (
        <span className="comet-body-s truncate text-foreground">
          <span>{AGGREGATION_VALUE.AVG}</span>
          <TooltipWrapper content={formatTooltip(statistic.value)}>
            <span className="ml-1 font-semibold">
              {dataFormater(statistic.value)}
            </span>
          </TooltipWrapper>
        </span>
      );
    }
    case STATISTIC_AGGREGATION_TYPE.COUNT:
      return (
        <span className="comet-body-s truncate text-foreground">
          <span>{statistic.type.toLowerCase()}</span>
          <TooltipWrapper content={formatTooltip(statistic.value)}>
            <span className="ml-1 font-semibold">
              {dataFormater(statistic.value)}
            </span>
          </TooltipWrapper>
        </span>
      );
    case STATISTIC_AGGREGATION_TYPE.PERCENTAGE:
      return (
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <div className="flex max-w-full">
              <span className="comet-body-s truncate text-foreground">
                <span>{selectedValue}</span>
                <TooltipWrapper
                  content={formatTooltip(
                    get(statistic.value, selectedValue, 0),
                  )}
                >
                  <span className="ml-1 font-semibold">
                    {dataFormater(get(statistic.value, selectedValue, 0))}
                  </span>
                </TooltipWrapper>
              </span>
              <ChevronDown className="ml-0.5 size-3.5 shrink-0"></ChevronDown>
            </div>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end">
            {PERCENTILE_OPTIONS.map((option) => {
              return (
                <DropdownMenuCheckboxItem
                  key={option.value}
                  onSelect={() =>
                    setSelectedValue(option.value as AggregationValue)
                  }
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
