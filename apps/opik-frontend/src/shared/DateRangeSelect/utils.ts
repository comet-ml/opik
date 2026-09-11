import dayjs from "dayjs";
import { DateRangePreset, DateRangeValue } from "./types";
import { PRESET_DATE_RANGES } from "./constants";
import { DateRange } from "react-day-picker";

export const getDisabledDates = (minDate?: Date, maxDate?: Date) => {
  if (!minDate && !maxDate) return undefined;

  return (date: Date): boolean => {
    if (minDate && dayjs(date).isBefore(minDate, "day")) {
      return true;
    }
    if (maxDate && dayjs(date).isAfter(maxDate, "day")) {
      return true;
    }
    return false;
  };
};

export const getRangeDatesText = (v: DateRangeValue): string => {
  const fromDate = dayjs(v.from).format("YYYY/MM/DD");
  const toDate = dayjs(v.to).format("YYYY/MM/DD");

  return `${fromDate} - ${toDate}`;
};

export const getRangePreset = (
  range: DateRangeValue,
): DateRangePreset | null => {
  const presets: DateRangePreset[] = [
    "past24hours",
    "past3days",
    "past7days",
    "past30days",
    "past60days",
    "alltime",
  ];

  for (const preset of presets) {
    const presetRange = PRESET_DATE_RANGES[preset];
    if (
      dayjs(range.from).isSame(presetRange.from, "day") &&
      dayjs(range.to).isSame(presetRange.to, "day")
    ) {
      return preset;
    }
  }

  return null;
};

// for custom DatePicker range selection behavior
export const customDayClick = (
  prev: DateRange | undefined,
  day: Date,
  onSuccess: (range: DateRangeValue) => void,
) => {
  let newRange: DateRange | undefined;

  if (prev?.to) {
    newRange = { from: day, to: undefined };
  } else if (prev?.from) {
    if (day < prev.from) {
      newRange = { from: day, to: undefined };
    } else {
      newRange = { from: prev.from, to: day };
    }
  } else {
    newRange = { from: day, to: undefined };
  }

  if (newRange?.from && newRange?.to) {
    onSuccess({
      from: newRange.from,
      to: dayjs(newRange.to).endOf("day").toDate(),
    });
  }

  return newRange;
};
