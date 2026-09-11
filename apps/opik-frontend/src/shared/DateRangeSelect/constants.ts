import { DateRangePreset, DateRangeValue } from "./types";
import dayjs from "dayjs";

export const PRESET_DATE_RANGES: Record<DateRangePreset, DateRangeValue> = {
  past24hours: {
    from: dayjs().subtract(24, "hours").startOf("hour").toDate(),
    to: dayjs().endOf("day").toDate(),
  },
  past3days: {
    from: dayjs().subtract(2, "days").startOf("day").toDate(),
    to: dayjs().endOf("day").toDate(),
  },
  past7days: {
    from: dayjs().subtract(6, "days").startOf("day").toDate(),
    to: dayjs().endOf("day").toDate(),
  },
  past30days: {
    from: dayjs().subtract(29, "days").startOf("day").toDate(),
    to: dayjs().endOf("day").toDate(),
  },
  past60days: {
    from: dayjs().subtract(59, "days").startOf("day").toDate(),
    to: dayjs().endOf("day").toDate(),
  },
  alltime: {
    from: dayjs().subtract(5, "years").startOf("day").toDate(),
    to: dayjs().endOf("day").toDate(),
  },
};

export const PRESET_LABEL_MAP: Record<DateRangePreset, string> = {
  past24hours: "Past 24 hours",
  past3days: "Past 3 days",
  past7days: "Past 7 days",
  past30days: "Past 30 days",
  past60days: "Past 60 days",
  alltime: "All time",
};
