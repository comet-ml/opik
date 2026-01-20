import dayjs from "dayjs";
import utc from "dayjs/plugin/utc";
import relativeTime from "dayjs/plugin/relativeTime";
import duration from "dayjs/plugin/duration";
import customParseFormat from "dayjs/plugin/customParseFormat";
import isString from "lodash/isString";
import round from "lodash/round";
import isUndefined from "lodash/isUndefined";
import isNull from "lodash/isNull";

import {
  TIMEZONE_TO_LOCALE,
  LOCALE_TO_PARSE_FORMATS,
  LOCALE_TO_PLACEHOLDER,
  DEFAULT_LOCALE,
  DEFAULT_DATE_PLACEHOLDER,
  FALLBACK_DATE_FORMATS,
} from "@/constants/dateLocale";

dayjs.extend(utc);
dayjs.extend(relativeTime);
dayjs.extend(duration);
dayjs.extend(customParseFormat);

type FormatDateConfig = {
  utc?: boolean;
  includeSeconds?: boolean;
};

/**
 * Get locale based on the user's timezone.
 * Falls back to en-US if timezone cannot be determined or is not mapped.
 */
const getLocaleFromTimezone = (): string => {
  try {
    const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone;

    // Direct match
    if (timezone && TIMEZONE_TO_LOCALE[timezone]) {
      return TIMEZONE_TO_LOCALE[timezone];
    }

    // Try to match by region prefix (e.g., "Europe/" -> use first European locale as hint)
    if (timezone) {
      const region = timezone.split("/")[0];
      // Find any timezone in the same region
      const regionMatch = Object.entries(TIMEZONE_TO_LOCALE).find(([tz]) =>
        tz.startsWith(region + "/"),
      );
      if (regionMatch) {
        return regionMatch[1];
      }
    }

    return DEFAULT_LOCALE;
  } catch {
    return DEFAULT_LOCALE;
  }
};

/**
 * Format a date string according to the user's geographic locale.
 * Uses timezone detection to infer the user's likely locale preference.
 *
 * @param value - ISO date string to format
 * @param config - Configuration options
 * @param config.utc - If true, display the date in UTC timezone
 * @param config.includeSeconds - If true, include seconds in the output
 * @returns Formatted date string in the user's locale, or empty string if invalid
 *
 * @example
 * // User in Europe/Warsaw sees: "11.01.2026, 14:30" (Polish format)
 * // User in America/New_York sees: "1/11/2026, 2:30 PM" (US format)
 * // User in Europe/London sees: "11/01/2026, 14:30" (UK format)
 * // User in Asia/Tokyo sees: "2026/01/11 14:30" (Japanese format)
 */
export const formatDate = (
  value: string,
  { utc = false, includeSeconds = false }: FormatDateConfig = {},
): string => {
  if (!isString(value) || !dayjs(value).isValid()) {
    return "";
  }

  const date = utc ? dayjs(value).utc().toDate() : dayjs(value).toDate();
  const locale = getLocaleFromTimezone();

  const options: Intl.DateTimeFormatOptions = {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    ...(includeSeconds && { second: "2-digit" }),
    ...(utc && { timeZone: "UTC" }),
  };

  try {
    return new Intl.DateTimeFormat(locale, options).format(date);
  } catch {
    // Fallback to unambiguous format if locale is not supported
    const fallbackFormat = includeSeconds
      ? FALLBACK_DATE_FORMATS.withSeconds
      : FALLBACK_DATE_FORMATS.withoutSeconds;
    return utc
      ? dayjs(value).utc().format(fallbackFormat)
      : dayjs(value).format(fallbackFormat);
  }
};

/**
 * Get the date format placeholder string for the user's locale.
 * This provides a user-friendly hint for the expected date format.
 *
 * @returns Placeholder string showing the expected date format
 *
 * @example
 * // User in Europe/Warsaw sees: "DD.MM.YYYY, HH:mm"
 * // User in America/New_York sees: "MM/DD/YYYY, hh:mm AM/PM"
 * // User in Asia/Tokyo sees: "YYYY/MM/DD HH:mm"
 */
export const getDateFormatPlaceholder = (): string => {
  const locale = getLocaleFromTimezone();

  // Try exact match
  if (LOCALE_TO_PLACEHOLDER[locale]) {
    return LOCALE_TO_PLACEHOLDER[locale];
  }

  // Try language-only match (e.g., "en" from "en-SG")
  const language = locale.split("-")[0];
  const languageMatch = Object.keys(LOCALE_TO_PLACEHOLDER).find((key) =>
    key.startsWith(language + "-"),
  );
  if (languageMatch) {
    return LOCALE_TO_PLACEHOLDER[languageMatch];
  }

  // Default fallback
  return DEFAULT_DATE_PLACEHOLDER;
};

/**
 * Get all supported parse formats derived from TIMEZONE_TO_LOCALE mapping.
 * This ensures validation supports all locales that formatDate can produce.
 */
const getSupportedParseFormats = (): string[] => {
  // Get unique locales from timezone mapping
  const uniqueLocales = [...new Set(Object.values(TIMEZONE_TO_LOCALE))];

  // Collect all formats for these locales
  const formats = new Set<string>();

  // Add formats for each unique locale
  uniqueLocales.forEach((locale) => {
    // Try exact match first
    if (LOCALE_TO_PARSE_FORMATS[locale]) {
      LOCALE_TO_PARSE_FORMATS[locale].forEach((f) => formats.add(f));
      return;
    }

    // Try language-only match (e.g., "en" from "en-SG")
    const language = locale.split("-")[0];
    const languageMatch = Object.keys(LOCALE_TO_PARSE_FORMATS).find((key) =>
      key.startsWith(language + "-"),
    );
    if (languageMatch) {
      LOCALE_TO_PARSE_FORMATS[languageMatch].forEach((f) => formats.add(f));
    }
  });

  // Always include fallback formats
  formats.add(FALLBACK_DATE_FORMATS.withoutSeconds);
  formats.add(FALLBACK_DATE_FORMATS.withSeconds);

  return [...formats];
};

// Cache the supported formats
const SUPPORTED_PARSE_FORMATS = getSupportedParseFormats();

/**
 * Validates if a string is a valid formatted date.
 * Supports all locale formats from TIMEZONE_TO_LOCALE mapping.
 *
 * @param value - The string to validate
 * @returns true if the string can be parsed as a valid date
 */
export const isStringValidFormattedDate = (value: string): boolean => {
  if (!isString(value) || value.trim() === "") {
    return false;
  }

  return SUPPORTED_PARSE_FORMATS.some((format) =>
    dayjs(value, format, true).isValid(),
  );
};

/**
 * Parses a formatted date string back to a Date object.
 * Supports all locale formats from TIMEZONE_TO_LOCALE mapping.
 *
 * @param value - The formatted date string to parse
 * @returns Date object if valid, undefined otherwise
 */
export const parseFormattedDate = (value: string): Date | undefined => {
  if (!isString(value) || value.trim() === "") {
    return undefined;
  }

  for (const format of SUPPORTED_PARSE_FORMATS) {
    const parsed = dayjs(value, format, true);
    if (parsed.isValid()) {
      return parsed.toDate();
    }
  }

  return undefined;
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
