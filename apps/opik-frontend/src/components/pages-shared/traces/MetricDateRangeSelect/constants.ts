import { DateRangePreset } from "@/components/shared/DateRangeSelect";
import dayjs from "dayjs";

export const DATE_RANGE_PRESET_PAST_24_HOURS: DateRangePreset = "past24hours";
export const DATE_RANGE_PRESET_PAST_3_DAYS: DateRangePreset = "past3days";
export const DATE_RANGE_PRESET_PAST_7_DAYS: DateRangePreset = "past7days";
export const DATE_RANGE_PRESET_PAST_30_DAYS: DateRangePreset = "past30days";
export const DATE_RANGE_PRESET_PAST_60_DAYS: DateRangePreset = "past60days";
export const DATE_RANGE_PRESET_ALLTIME: DateRangePreset = "alltime";

export const DEFAULT_DATE_PRESET = DATE_RANGE_PRESET_PAST_30_DAYS;
export const DEFAULT_DATE_URL_KEY = "time_range";

export const MIN_METRICS_DATE = dayjs().subtract(1, "year").toDate();
export const MAX_METRICS_DATE = dayjs().toDate();
