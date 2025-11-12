import { INTERVAL_TYPE } from "@/api/projects/useProjectMetric";
import {
  DateRangeValue,
  getRangePreset,
} from "@/components/shared/DateRangeSelect";
import dayjs from "dayjs";

export const serializeDateForURL = (date: Date): string => {
  return dayjs(date).format("YYYY-MM-DD");
};

export const parseDateFromState = (dateString: string): Date => {
  return dayjs(dateString, "YYYY-MM-DD").toDate();
};

export const serializeDateRange = (range: DateRangeValue): string => {
  return `${serializeDateForURL(range.from)},${serializeDateForURL(range.to)}`;
};

export const parseDateRangeFromState = (
  value: string,
  defaultRange: DateRangeValue,
  minDate: Date,
  maxDate: Date,
): DateRangeValue => {
  if (!value || !value.includes(",")) {
    return defaultRange;
  }

  const [fromStr, toStr] = value.split(",");
  try {
    const from = parseDateFromState(fromStr);
    const to = parseDateFromState(toStr);

    if (!dayjs(from).isValid() || !dayjs(to).isValid()) {
      return defaultRange;
    }

    const parsedRange = { from, to };

    // Note: "alltime" is allowed during parsing here, but at the UI level (e.g., MetricsTab.tsx),
    // it is immediately replaced with DEFAULT_METRICS_DATE_RANGE. Thus, "alltime" is never actually used for filtering.
    const preset = getRangePreset(parsedRange);
    if (preset === "alltime") {
      return parsedRange;
    }

    // For other ranges, validate against min/max dates
    if (dayjs(from).isBefore(minDate) || dayjs(to).isAfter(maxDate)) {
      return defaultRange;
    }

    return parsedRange;
  } catch {
    return defaultRange;
  }
};

export const calculateIntervalType = (
  dateRange: DateRangeValue,
): INTERVAL_TYPE => {
  const daysDiff = dayjs(dateRange.to).diff(dayjs(dateRange.from), "days");

  if (daysDiff <= 3) {
    return INTERVAL_TYPE.HOURLY;
  }

  if (daysDiff <= 30) {
    return INTERVAL_TYPE.DAILY;
  }

  return INTERVAL_TYPE.WEEKLY;
};

export const calculateIntervalStartAndEnd = (
  dateRange: DateRangeValue,
): { intervalStart: string; intervalEnd: string } => {
  // Calculate the number of days in the original range
  const daysDiff = dayjs(dateRange.to).diff(dayjs(dateRange.from), "days");
  const startOf = daysDiff <= 1 ? "hour" : "day";

  // Check if the end date is today
  const isEndDateToday = dayjs(dateRange.to).isSame(dayjs(), "day");

  let endTime: dayjs.Dayjs;
  let startTime: dayjs.Dayjs;

  if (isEndDateToday) {
    // If end date is today, use current moment in UTC
    endTime = dayjs().utc();
    // Start time = current time minus the number of days in the original range
    startTime = endTime.subtract(daysDiff || 1, "days").startOf(startOf);
  } else {
    // If end date is not today, use the selected date range as-is
    endTime = dayjs(dateRange.to).utc().endOf("day");
    startTime = dayjs(dateRange.from).utc().startOf(startOf);
  }

  return {
    intervalStart: startTime.format(),
    intervalEnd: endTime.format(),
  };
};
