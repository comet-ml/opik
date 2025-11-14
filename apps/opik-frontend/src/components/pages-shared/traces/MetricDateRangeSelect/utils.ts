import { INTERVAL_TYPE } from "@/api/projects/useProjectMetric";
import {
  DateRangeValue,
  getRangePreset,
  DateRangePreset,
  PRESET_DATE_RANGES,
} from "@/components/shared/DateRangeSelect";
import dayjs from "dayjs";
import { DATE_RANGE_PRESET_ALLTIME, DEFAULT_DATE_PRESET } from "./constants";

export const serializeDateForURL = (date: Date): string => {
  return dayjs(date).format("YYYY-MM-DD");
};

export const parseDateFromState = (dateString: string): Date => {
  return dayjs(dateString, "YYYY-MM-DD").toDate();
};

export const serializeDateRange = (range: DateRangeValue): string => {
  const preset = getRangePreset(range);
  if (preset) {
    return preset;
  }
  return `${serializeDateForURL(range.from)},${serializeDateForURL(range.to)}`;
};

export const parseDateRangeFromState = (
  value: string,
  minDate: Date,
  maxDate: Date,
  defaultValue: DateRangePreset = DEFAULT_DATE_PRESET,
): DateRangeValue => {
  if (!value) {
    return PRESET_DATE_RANGES[defaultValue];
  }

  if (value in PRESET_DATE_RANGES) {
    return PRESET_DATE_RANGES[value as DateRangePreset];
  }

  if (!value.includes(",")) {
    return PRESET_DATE_RANGES[defaultValue];
  }

  const [fromStr, toStr] = value.split(",");
  try {
    const from = parseDateFromState(fromStr);
    const to = parseDateFromState(toStr);

    if (!dayjs(from).isValid() || !dayjs(to).isValid()) {
      return PRESET_DATE_RANGES[defaultValue];
    }

    const parsedRange = { from, to };

    if (dayjs(from).isBefore(minDate) || dayjs(to).isAfter(maxDate)) {
      return PRESET_DATE_RANGES[defaultValue];
    }

    return parsedRange;
  } catch {
    return PRESET_DATE_RANGES[defaultValue];
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
): { intervalStart: string; intervalEnd: string | undefined } => {
  const daysDiff = dayjs(dateRange.to).diff(dayjs(dateRange.from), "days");
  const startOf = daysDiff <= 1 ? "hour" : "day";

  const isEndDateToday = dayjs(dateRange.to).isSame(dayjs(), "day");
  const preset = getRangePreset(dateRange);
  const isPresetRange = preset && preset !== DATE_RANGE_PRESET_ALLTIME;

  let endTime: dayjs.Dayjs | undefined;
  let startTime: dayjs.Dayjs;

  if (isEndDateToday) {
    if (isPresetRange) {
      endTime = undefined;
      startTime = dayjs()
        .utc()
        .subtract(daysDiff || 1, "days")
        .startOf(startOf);
    } else {
      endTime = dayjs().utc();
      startTime = endTime.subtract(daysDiff || 1, "days").startOf(startOf);
    }
  } else {
    endTime = dayjs(dateRange.to).utc().endOf("day");
    startTime = dayjs(dateRange.from).utc().startOf(startOf);
  }

  return {
    intervalStart: startTime.format(),
    intervalEnd: endTime?.format(),
  };
};
