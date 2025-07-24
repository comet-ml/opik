import { useQueryParam, withDefault, StringParam } from "use-query-params";
import { useMemo } from "react";
import { DateRangeValue } from "@/components/shared/DateRangeSelect";
import {
  calculateIntervalType,
  calculateIntervalStartAndEnd,
  parseDateRangeFromURL,
  serializeDateRange,
} from "./utils";
import { INTERVAL_TYPE } from "@/api/projects/useProjectMetric";
import {
  DEFAULT_METRICS_DATE_RANGE,
  MIN_METRICS_DATE,
  MAX_METRICS_DATE,
} from "./constants";

interface UseMetricDateRangeStateOptions {
  defaultDateRange?: DateRangeValue;
  minDate?: Date;
  maxDate?: Date;
}

export const useMetricDateRangeState = (
  options: UseMetricDateRangeStateOptions = {},
) => {
  const {
    defaultDateRange = DEFAULT_METRICS_DATE_RANGE,
    minDate = MIN_METRICS_DATE,
    maxDate = MAX_METRICS_DATE,
  } = options;

  const [dateRangeParam, setDateRangeParam] = useQueryParam(
    "range",
    withDefault(StringParam, serializeDateRange(defaultDateRange)),
    {
      updateType: "replaceIn",
    },
  );

  const dateRange = useMemo(
    () =>
      parseDateRangeFromURL(dateRangeParam, defaultDateRange, minDate, maxDate),
    [dateRangeParam, defaultDateRange, minDate, maxDate],
  );

  const handleDateRangeChange = (newRange: DateRangeValue) => {
    setDateRangeParam(serializeDateRange(newRange));
  };

  const interval: INTERVAL_TYPE = useMemo(
    () => calculateIntervalType(dateRange),
    [dateRange],
  );

  const { intervalStart, intervalEnd } = useMemo(
    () => calculateIntervalStartAndEnd(dateRange),
    [dateRange],
  );

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
