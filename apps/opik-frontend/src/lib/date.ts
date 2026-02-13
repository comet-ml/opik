import dayjs from "dayjs";
import utc from "dayjs/plugin/utc";
import duration from "dayjs/plugin/duration";
import customParseFormat from "dayjs/plugin/customParseFormat";
import isString from "lodash/isString";
import round from "lodash/round";
import isUndefined from "lodash/isUndefined";
import isNull from "lodash/isNull";
import { getDateFormatFromLocalStorage } from "@/hooks/useDateFormat";

dayjs.extend(utc);
dayjs.extend(duration);
dayjs.extend(customParseFormat);

type FormatDateConfig = {
  utc?: boolean;
  includeSeconds?: boolean;
  format?: string;
};

export const formatDate = (
  value: string,
  { utc = false, includeSeconds = false, format }: FormatDateConfig = {},
) => {
  const storedFormat = getDateFormatFromLocalStorage();

  let dateTimeFormat = format || storedFormat;

  if (!format && includeSeconds) {
    dateTimeFormat = includeSeconds
      ? "MM/DD/YY hh:mm:ss A"
      : "MM/DD/YY hh:mm A";
  }

  if (isString(value) && dayjs(value).isValid()) {
    if (utc) {
      return dayjs(value).utc().format(dateTimeFormat);
    }

    return dayjs(value).format(dateTimeFormat);
  }
  return "";
};

export const getTimeFromNow = (value: string): string => {
  if (!isString(value)) return "";

  const date = dayjs(value);
  if (!date.isValid()) return "";

  const now = dayjs();
  const diffMinutes = now.diff(date, "minute");
  const diffHours = now.diff(date, "hour");
  const diffDays = now.diff(date, "day");

  if (diffMinutes < 0) return date.format("MMM D, YYYY");
  if (diffMinutes < 1) return "< 1 min ago";
  if (diffMinutes < 60) return `${diffMinutes} mins ago`;
  if (diffHours < 24)
    return `${diffHours} ${diffHours === 1 ? "hour" : "hours"} ago`;
  if (diffDays <= 7)
    return `${diffDays} ${diffDays === 1 ? "day" : "days"} ago`;

  return date.year() === now.year()
    ? date.format("MMM D")
    : date.format("MMM D, YYYY");
};

export const makeStartOfMinute = (value: string) => {
  return dayjs(value).startOf("minute").toISOString();
};

export const makeEndOfMinute = (value: string) => {
  return dayjs(value).endOf("minute").toISOString();
};

export const millisecondsToSeconds = (milliseconds: number) => {
  const precision = milliseconds > 100 ? 1 : milliseconds > 10 ? 2 : 3;
  return round(milliseconds / 1000, precision);
};

export const secondsToMilliseconds = (seconds: number) => {
  return seconds * 1000;
};

export const formatDuration = (value?: number | null, onlySeconds = true) => {
  if (isUndefined(value) || isNull(value) || isNaN(value)) {
    return "NA";
  }

  const totalSeconds = millisecondsToSeconds(value);

  if (onlySeconds) {
    return `${totalSeconds}s`;
  } else {
    let years = 0,
      months = 0,
      weeks = 0,
      days = 0,
      hours = 0,
      minutes = 0,
      seconds = totalSeconds;
    if (seconds >= 60 * 60 * 24 * 365) {
      years = Math.floor(seconds / (60 * 60 * 24 * 365));
      seconds %= 60 * 60 * 24 * 365;
    }
    if (seconds >= 60 * 60 * 24 * 30) {
      months = Math.floor(seconds / (60 * 60 * 24 * 30));
      seconds %= 60 * 60 * 24 * 30;
    }
    if (seconds >= 60 * 60 * 24 * 7) {
      weeks = Math.floor(seconds / (60 * 60 * 24 * 7));
      seconds %= 60 * 60 * 24 * 7;
    }
    if (seconds >= 60 * 60 * 24) {
      days = Math.floor(seconds / (60 * 60 * 24));
      seconds %= 60 * 60 * 24;
    }
    if (seconds >= 60 * 60) {
      hours = Math.floor(seconds / (60 * 60));
      seconds %= 60 * 60;
    }
    if (seconds >= 60) {
      minutes = Math.floor(seconds / 60);
      seconds = round(seconds % 60, 1);
    }

    const result = `${years ? years + "y " : ""}${
      months ? months + "mth " : ""
    }${weeks ? weeks + "w " : ""}${days ? days + "d " : ""}${
      hours ? hours + "h " : ""
    }${minutes ? minutes + "m " : ""}${!seconds ? "" : seconds + "s"}`.trim();

    return result || "0s";
  }
};

/**
 * Validates an ISO-8601 duration string and checks if it's within the maximum allowed duration
 * @param durationString - ISO-8601 duration string (e.g., "PT30M", "P1D", "PT2H30M")
 * @param maxDays - Maximum allowed duration in days (default: 7)
 * @returns boolean indicating if the duration is valid and within limits
 */
export const isValidIso8601Duration = (
  durationString: string,
  maxDays: number = 7,
): boolean => {
  try {
    const dur = dayjs.duration(durationString);
    const totalMs = dur.asMilliseconds();

    if (isNaN(totalMs) || totalMs <= 0) {
      return false;
    }

    const maxDuration = dayjs.duration(maxDays, "days");
    return totalMs <= maxDuration.asMilliseconds();
  } catch {
    return false;
  }
};

/**
 * Converts an ISO-8601 duration string to a human-readable format
 * @param durationString - ISO-8601 duration string
 * @returns Human-readable duration string or "NA" if invalid
 */
export const formatIso8601Duration = (durationString: string): string => {
  try {
    const dur = dayjs.duration(durationString);
    const totalMs = dur.asMilliseconds();

    return formatDuration(totalMs, false);
  } catch {
    return "NA";
  }
};
