import { useCallback, useMemo } from "react";
import {
  DateRangeValue,
  getRangePreset,
} from "@/components/shared/DateRangeSelect";
import {
  calculateIntervalType,
  calculateIntervalStartAndEnd,
  parseDateRangeFromState,
  serializeDateRange,
} from "./utils";
import { INTERVAL_TYPE } from "@/api/projects/useProjectMetric";
import { MIN_METRICS_DATE, MAX_METRICS_DATE } from "./constants";

type UseMetricDateRangeCoreProps = {
  value: string;
  setValue: (value: string) => void;
  defaultDateRange: DateRangeValue;
  minDate?: Date;
  maxDate?: Date;
};

export type UseMetricDateRangeOptions = Omit<
  UseMetricDateRangeCoreProps,
  "value" | "setValue" | "defaultDateRange"
>;

export const useMetricDateRangeCore = ({
  value,
  setValue,
  defaultDateRange,
  minDate = MIN_METRICS_DATE,
  maxDate = MAX_METRICS_DATE,
}: UseMetricDateRangeCoreProps) => {
  const dateRange = useMemo(
    () => parseDateRangeFromState(value, defaultDateRange, minDate, maxDate),
    [value, defaultDateRange, minDate, maxDate],
  );

  const handleDateRangeChange = useCallback(
    (newRange: DateRangeValue) => {
      setValue(serializeDateRange(newRange));
    },
    [setValue],
  );

  const isAllTime = useMemo(() => {
    return getRangePreset(dateRange) === "alltime";
  }, [dateRange]);

  const interval: INTERVAL_TYPE = useMemo(
    () => calculateIntervalType(dateRange),
    [dateRange],
  );

  const { intervalStart, intervalEnd } = useMemo(() => {
    if (isAllTime) {
      return { intervalStart: undefined, intervalEnd: undefined };
    }
    return calculateIntervalStartAndEnd(dateRange);
  }, [dateRange, isAllTime]);

  return {
    dateRange,
    handleDateRangeChange,
    interval,
    intervalStart: intervalStart as string | undefined,
    intervalEnd: intervalEnd as string | undefined,
    minDate,
    maxDate,
  };
};
