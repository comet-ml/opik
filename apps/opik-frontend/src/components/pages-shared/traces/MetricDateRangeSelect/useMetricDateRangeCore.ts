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
import {
  MIN_METRICS_DATE,
  MAX_METRICS_DATE,
  DATE_RANGE_PRESET_ALLTIME,
  DEFAULT_DATE_PRESET,
} from "./constants";
import { DateRangePreset } from "@/components/shared/DateRangeSelect";

type UseMetricDateRangeCoreProps = {
  value: string;
  setValue: (value: string) => void;
  defaultValue?: DateRangePreset;
  minDate?: Date;
  maxDate?: Date;
};

export type UseMetricDateRangeOptions = Omit<
  UseMetricDateRangeCoreProps,
  "value" | "setValue"
>;

export const useMetricDateRangeCore = ({
  value,
  setValue,
  defaultValue = DEFAULT_DATE_PRESET,
  minDate = MIN_METRICS_DATE,
  maxDate = MAX_METRICS_DATE,
}: UseMetricDateRangeCoreProps) => {
  const dateRange = useMemo(
    () => parseDateRangeFromState(value, minDate, maxDate, defaultValue),
    [value, minDate, maxDate, defaultValue],
  );

  const handleDateRangeChange = useCallback(
    (newRange: DateRangeValue) => {
      setValue(serializeDateRange(newRange));
    },
    [setValue],
  );

  const isAllTime = useMemo(() => {
    return getRangePreset(dateRange) === DATE_RANGE_PRESET_ALLTIME;
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
    intervalStart,
    intervalEnd,
    minDate,
    maxDate,
  };
};
