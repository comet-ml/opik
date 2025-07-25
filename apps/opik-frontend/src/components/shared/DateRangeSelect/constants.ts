import { DateRangePreset, DateRangeValue } from "./types";
import dayjs from "dayjs";

export const PRESET_DATE_RANGES: Record<
  Exclude<DateRangePreset, "customDates">,
  DateRangeValue
> = {
  today: {
    from: dayjs().startOf("day").toDate(),
    to: dayjs().endOf("day").toDate(),
  },
  last3days: {
    from: dayjs().subtract(2, "days").startOf("day").toDate(),
    to: dayjs().endOf("day").toDate(),
  },
  lastWeek: {
    from: dayjs().subtract(6, "days").startOf("day").toDate(),
    to: dayjs().endOf("day").toDate(),
  },
  lastMonth: {
    from: dayjs().subtract(29, "days").startOf("day").toDate(),
    to: dayjs().endOf("day").toDate(),
  },
};

export const PRESET_LABEL_MAP: Record<DateRangePreset, string> = {
  today: "Today",
  last3days: "Last 3 days",
  lastWeek: "Last week",
  lastMonth: "Last month",
};
