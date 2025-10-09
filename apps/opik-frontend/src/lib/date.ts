import dayjs from "dayjs";
import utc from "dayjs/plugin/utc";
import relativeTime from "dayjs/plugin/relativeTime";
import duration from "dayjs/plugin/duration";
import isString from "lodash/isString";
import round from "lodash/round";
import isUndefined from "lodash/isUndefined";
import isNull from "lodash/isNull";

dayjs.extend(utc);
dayjs.extend(relativeTime);
dayjs.extend(duration);

const FORMATTED_DATE_STRING_REGEXP =
  /^(0[1-9]|1[0-2])\/(0[1-9]|[12][0-9]|3[01])\/(\d{2}|\d{4})\s(0[1-9]|1[0-2]):([0-5][0-9])\s(AM|PM)$/;

type FormatDateConfig = {
  utc?: boolean;
  includeSeconds?: boolean;
};

export const formatDate = (
  value: string,
  { utc = false, includeSeconds = false }: FormatDateConfig = {},
) => {
  const dateTimeFormat = includeSeconds
    ? "MM/DD/YY hh:mm:ss A"
    : "MM/DD/YY hh:mm A";

  if (isString(value) && dayjs(value).isValid()) {
    if (utc) {
      return dayjs(value).utc().format(dateTimeFormat);
    }

    return dayjs(value).format(dateTimeFormat);
  }
  return "";
};

export const isStringValidFormattedDate = (value: string) => {
  return isString(value) && FORMATTED_DATE_STRING_REGEXP.test(value);
};

export const getTimeFromNow = (date: string) => {
  if (isString(date) && dayjs(date).isValid()) {
    return dayjs().to(dayjs(date));
  }
  return "";
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
