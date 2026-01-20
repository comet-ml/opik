import { describe, expect, it } from "vitest";
import {
  millisecondsToSeconds,
  formatDate,
  isStringValidFormattedDate,
  parseFormattedDate,
  getDateFormatPlaceholder,
  getTimeFromNow,
  makeStartOfMinute,
  makeEndOfMinute,
  secondsToMilliseconds,
  formatDuration,
  isValidIso8601Duration,
  formatIso8601Duration,
} from "./date";

describe("millisecondsToSeconds", () => {
  it("should return seconds with precision 3 when milliseconds <= 5", () => {
    expect(millisecondsToSeconds(5)).toBe(0.005);
    expect(millisecondsToSeconds(1)).toBe(0.001);
  });

  it("should return seconds with precision 2 when 5 < milliseconds <= 50", () => {
    expect(millisecondsToSeconds(50)).toBe(0.05);
    expect(millisecondsToSeconds(25)).toBe(0.03);
  });

  it("should return seconds with precision 1 when milliseconds > 50", () => {
    expect(millisecondsToSeconds(100)).toBe(0.1);
    expect(millisecondsToSeconds(1000)).toBe(1);
  });
});

describe("formatDate", () => {
  const testDate = "2026-01-11T14:30:00.000Z";

  it("should return empty string for invalid date", () => {
    expect(formatDate("invalid")).toBe("");
    expect(formatDate("")).toBe("");
  });

  it("should format date using system locale", () => {
    // formatDate uses Intl.DateTimeFormat(undefined, ...) which respects system locale
    // We just verify it returns a non-empty string with expected date components
    const result = formatDate(testDate, { utc: true });
    expect(result).toBeTruthy();
    expect(result.length).toBeGreaterThan(0);
    // Should contain year 2026 somewhere in the output
    expect(result).toContain("2026");
  });

  it("should include seconds when includeSeconds is true", () => {
    const result = formatDate(testDate, { utc: true, includeSeconds: true });
    // Should include :00 for seconds somewhere in the output
    expect(result).toMatch(/:00/);
  });

  it("should handle UTC option correctly", () => {
    // When utc is true, should show UTC time (14:30 or 2:30 PM depending on locale)
    const utcResult = formatDate(testDate, { utc: true });
    // The time should be 14:30 UTC - check for either 24h or 12h format
    expect(utcResult).toMatch(/14:30|2:30/);
  });

  it("should format valid ISO date string", () => {
    const result = formatDate("2026-06-15T09:45:30.000Z", { utc: true });
    expect(result).toBeTruthy();
    expect(result).toContain("2026");
    // Should contain the time components
    expect(result).toMatch(/9:45|09:45/);
  });
});

describe("isStringValidFormattedDate", () => {
  it("should return false for invalid inputs", () => {
    expect(isStringValidFormattedDate("")).toBe(false);
    expect(isStringValidFormattedDate("invalid")).toBe(false);
    expect(isStringValidFormattedDate("not a date")).toBe(false);
  });

  it("should validate US format (en-US)", () => {
    expect(isStringValidFormattedDate("1/11/2026, 2:30 PM")).toBe(true);
    expect(isStringValidFormattedDate("01/11/2026, 2:30 PM")).toBe(true);
    expect(isStringValidFormattedDate("1/11/2026, 2:30:00 PM")).toBe(true);
  });

  it("should validate UK format (en-GB)", () => {
    expect(isStringValidFormattedDate("11/01/2026, 14:30")).toBe(true);
    expect(isStringValidFormattedDate("11/01/2026, 14:30:00")).toBe(true);
  });

  it("should validate German format (de-DE)", () => {
    expect(isStringValidFormattedDate("11.01.2026, 14:30")).toBe(true);
    expect(isStringValidFormattedDate("11.01.2026, 14:30:00")).toBe(true);
  });

  it("should validate Japanese format (ja-JP)", () => {
    expect(isStringValidFormattedDate("2026/01/11 14:30")).toBe(true);
    expect(isStringValidFormattedDate("2026/01/11 14:30:00")).toBe(true);
  });

  it("should validate fallback format", () => {
    expect(isStringValidFormattedDate("Jan 11, 2026 2:30 PM")).toBe(true);
    expect(isStringValidFormattedDate("Jan 11, 2026 2:30:00 PM")).toBe(true);
  });
});

describe("parseFormattedDate", () => {
  it("should return undefined for invalid inputs", () => {
    expect(parseFormattedDate("")).toBeUndefined();
    expect(parseFormattedDate("invalid")).toBeUndefined();
    expect(parseFormattedDate("not a date")).toBeUndefined();
  });

  it("should parse US format (en-US)", () => {
    const result = parseFormattedDate("1/11/2026, 2:30 PM");
    expect(result).toBeInstanceOf(Date);
    expect(result?.getFullYear()).toBe(2026);
    expect(result?.getMonth()).toBe(0); // January is 0
    expect(result?.getDate()).toBe(11);
    expect(result?.getHours()).toBe(14);
    expect(result?.getMinutes()).toBe(30);
  });

  it("should parse UK format (en-GB)", () => {
    // Note: DD/MM/YYYY format is ambiguous with MM/DD/YYYY
    // Using an unambiguous date (day > 12) to ensure correct parsing
    const result = parseFormattedDate("25/01/2026, 14:30");
    expect(result).toBeInstanceOf(Date);
    expect(result?.getFullYear()).toBe(2026);
    expect(result?.getMonth()).toBe(0); // January is 0
    expect(result?.getDate()).toBe(25);
    expect(result?.getHours()).toBe(14);
    expect(result?.getMinutes()).toBe(30);
  });

  it("should parse German format (de-DE)", () => {
    // Using an unambiguous date (day > 12) to ensure correct parsing
    const result = parseFormattedDate("25.01.2026, 14:30");
    expect(result).toBeInstanceOf(Date);
    expect(result?.getFullYear()).toBe(2026);
    expect(result?.getMonth()).toBe(0); // January is 0
    expect(result?.getDate()).toBe(25);
    expect(result?.getHours()).toBe(14);
    expect(result?.getMinutes()).toBe(30);
  });

  it("should parse Japanese format (ja-JP)", () => {
    const result = parseFormattedDate("2026/01/11 14:30");
    expect(result).toBeInstanceOf(Date);
    expect(result?.getFullYear()).toBe(2026);
    expect(result?.getMonth()).toBe(0); // January is 0
    expect(result?.getDate()).toBe(11);
    expect(result?.getHours()).toBe(14);
    expect(result?.getMinutes()).toBe(30);
  });

  it("should parse fallback format", () => {
    const result = parseFormattedDate("Jan 11, 2026 2:30 PM");
    expect(result).toBeInstanceOf(Date);
    expect(result?.getFullYear()).toBe(2026);
    expect(result?.getMonth()).toBe(0); // January is 0
    expect(result?.getDate()).toBe(11);
    expect(result?.getHours()).toBe(14);
    expect(result?.getMinutes()).toBe(30);
  });

  it("should parse Swedish format (sv-SE)", () => {
    const result = parseFormattedDate("2026-01-11, 14:30");
    expect(result).toBeInstanceOf(Date);
    expect(result?.getFullYear()).toBe(2026);
    expect(result?.getMonth()).toBe(0);
    expect(result?.getDate()).toBe(11);
    expect(result?.getHours()).toBe(14);
    expect(result?.getMinutes()).toBe(30);
  });

  it("should parse Dutch format (nl-NL)", () => {
    const result = parseFormattedDate("25-01-2026, 14:30");
    expect(result).toBeInstanceOf(Date);
    expect(result?.getFullYear()).toBe(2026);
    expect(result?.getMonth()).toBe(0);
    expect(result?.getDate()).toBe(25);
    expect(result?.getHours()).toBe(14);
    expect(result?.getMinutes()).toBe(30);
  });

  it("should parse dates with seconds", () => {
    const result = parseFormattedDate("1/11/2026, 2:30:45 PM");
    expect(result).toBeInstanceOf(Date);
    expect(result?.getSeconds()).toBe(45);
  });
});

describe("getDateFormatPlaceholder", () => {
  it("should return a non-empty placeholder string", () => {
    const placeholder = getDateFormatPlaceholder();
    expect(placeholder).toBeTruthy();
    expect(placeholder.length).toBeGreaterThan(0);
  });

  it("should contain date format indicators", () => {
    const placeholder = getDateFormatPlaceholder();
    // Should contain some combination of date format tokens
    expect(placeholder).toMatch(/[DMY]/i);
  });

  it("should contain time format indicators", () => {
    const placeholder = getDateFormatPlaceholder();
    // Should contain hour and minute indicators
    expect(placeholder).toMatch(/[Hh]/);
    expect(placeholder).toMatch(/mm/);
  });
});

describe("getTimeFromNow", () => {
  it("should return empty string for invalid date", () => {
    expect(getTimeFromNow("invalid")).toBe("");
    expect(getTimeFromNow("")).toBe("");
  });

  it("should return relative time for valid past date", () => {
    const pastDate = new Date();
    pastDate.setDate(pastDate.getDate() - 1);
    const result = getTimeFromNow(pastDate.toISOString());
    expect(result).toContain("day");
  });

  it("should return relative time for valid future date", () => {
    const futureDate = new Date();
    futureDate.setDate(futureDate.getDate() + 1);
    const result = getTimeFromNow(futureDate.toISOString());
    expect(result).toContain("day");
  });

  it("should handle dates from hours ago", () => {
    const hoursAgo = new Date();
    hoursAgo.setHours(hoursAgo.getHours() - 3);
    const result = getTimeFromNow(hoursAgo.toISOString());
    expect(result).toContain("hour");
  });
});

describe("makeStartOfMinute", () => {
  it("should return ISO string at start of minute", () => {
    const result = makeStartOfMinute("2026-01-11T14:30:45.123Z");
    expect(result).toBe("2026-01-11T14:30:00.000Z");
  });

  it("should handle different times", () => {
    const result = makeStartOfMinute("2026-06-15T09:45:30.500Z");
    expect(result).toBe("2026-06-15T09:45:00.000Z");
  });
});

describe("makeEndOfMinute", () => {
  it("should return ISO string at end of minute", () => {
    const result = makeEndOfMinute("2026-01-11T14:30:45.123Z");
    expect(result).toBe("2026-01-11T14:30:59.999Z");
  });

  it("should handle different times", () => {
    const result = makeEndOfMinute("2026-06-15T09:45:30.500Z");
    expect(result).toBe("2026-06-15T09:45:59.999Z");
  });
});

describe("secondsToMilliseconds", () => {
  it("should convert seconds to milliseconds", () => {
    expect(secondsToMilliseconds(1)).toBe(1000);
    expect(secondsToMilliseconds(0.5)).toBe(500);
    expect(secondsToMilliseconds(60)).toBe(60000);
  });

  it("should handle zero", () => {
    expect(secondsToMilliseconds(0)).toBe(0);
  });

  it("should handle decimal values", () => {
    expect(secondsToMilliseconds(1.5)).toBe(1500);
    expect(secondsToMilliseconds(0.001)).toBe(1);
  });
});

describe("formatDuration", () => {
  it("should return NA for invalid values", () => {
    expect(formatDuration(undefined)).toBe("NA");
    expect(formatDuration(null)).toBe("NA");
    expect(formatDuration(NaN)).toBe("NA");
  });

  it("should format milliseconds to seconds (onlySeconds=true)", () => {
    expect(formatDuration(1000)).toBe("1s");
    expect(formatDuration(5000)).toBe("5s");
    expect(formatDuration(500)).toBe("0.5s");
  });

  it("should format small milliseconds with precision", () => {
    expect(formatDuration(5)).toBe("0.005s");
    expect(formatDuration(50)).toBe("0.05s");
    expect(formatDuration(100)).toBe("0.1s");
  });

  it("should format duration with full breakdown (onlySeconds=false)", () => {
    // 1 minute
    expect(formatDuration(60000, false)).toBe("1m");
    // 1 hour
    expect(formatDuration(3600000, false)).toBe("1h");
    // 1 day
    expect(formatDuration(86400000, false)).toBe("1d");
  });

  it("should format complex durations", () => {
    // 1 hour, 30 minutes, 45 seconds
    const ms = (1 * 60 * 60 + 30 * 60 + 45) * 1000;
    const result = formatDuration(ms, false);
    expect(result).toContain("1h");
    expect(result).toContain("30m");
    expect(result).toContain("45s");
  });

  it("should handle zero duration", () => {
    expect(formatDuration(0)).toBe("0s");
    expect(formatDuration(0, false)).toBe("0s");
  });

  it("should format weeks", () => {
    // 2 weeks
    const twoWeeks = 14 * 24 * 60 * 60 * 1000;
    const result = formatDuration(twoWeeks, false);
    expect(result).toContain("2w");
  });

  it("should format months", () => {
    // ~2 months (60 days)
    const twoMonths = 60 * 24 * 60 * 60 * 1000;
    const result = formatDuration(twoMonths, false);
    expect(result).toContain("mth");
  });

  it("should format years", () => {
    // 1 year
    const oneYear = 365 * 24 * 60 * 60 * 1000;
    const result = formatDuration(oneYear, false);
    expect(result).toContain("1y");
  });
});

describe("isValidIso8601Duration", () => {
  it("should return true for valid durations within limit", () => {
    expect(isValidIso8601Duration("PT30M")).toBe(true); // 30 minutes
    expect(isValidIso8601Duration("PT1H")).toBe(true); // 1 hour
    expect(isValidIso8601Duration("P1D")).toBe(true); // 1 day
    expect(isValidIso8601Duration("PT2H30M")).toBe(true); // 2 hours 30 minutes
  });

  it("should return false for durations exceeding default limit (7 days)", () => {
    expect(isValidIso8601Duration("P8D")).toBe(false); // 8 days
    expect(isValidIso8601Duration("P14D")).toBe(false); // 14 days
  });

  it("should respect custom maxDays parameter", () => {
    expect(isValidIso8601Duration("P10D", 14)).toBe(true); // 10 days with 14 day limit
    expect(isValidIso8601Duration("P3D", 2)).toBe(false); // 3 days with 2 day limit
  });

  it("should return false for invalid duration strings", () => {
    expect(isValidIso8601Duration("invalid")).toBe(false);
    expect(isValidIso8601Duration("")).toBe(false);
    expect(isValidIso8601Duration("P0D")).toBe(false); // Zero duration
  });

  it("should return false for negative durations", () => {
    expect(isValidIso8601Duration("PT-30M")).toBe(false);
  });

  it("should handle edge cases at the limit", () => {
    expect(isValidIso8601Duration("P7D")).toBe(true); // Exactly 7 days
    expect(isValidIso8601Duration("P7DT1S")).toBe(false); // Just over 7 days
  });
});

describe("formatIso8601Duration", () => {
  it("should format ISO-8601 duration to human readable", () => {
    expect(formatIso8601Duration("PT30M")).toBe("30m");
    expect(formatIso8601Duration("PT1H")).toBe("1h");
    expect(formatIso8601Duration("P1D")).toBe("1d");
  });

  it("should format complex durations", () => {
    const result = formatIso8601Duration("PT2H30M");
    expect(result).toContain("2h");
    expect(result).toContain("30m");
  });

  it("should return NA for invalid duration strings", () => {
    expect(formatIso8601Duration("invalid")).toBe("NA");
    expect(formatIso8601Duration("")).toBe("NA");
  });

  it("should handle days and hours", () => {
    const result = formatIso8601Duration("P1DT2H");
    expect(result).toContain("1d");
    expect(result).toContain("2h");
  });
});

describe("isStringValidFormattedDate - additional formats", () => {
  it("should validate Swedish format (sv-SE)", () => {
    expect(isStringValidFormattedDate("2026-01-11, 14:30")).toBe(true);
    expect(isStringValidFormattedDate("2026-01-11 14:30")).toBe(true);
  });

  it("should validate Dutch format (nl-NL)", () => {
    expect(isStringValidFormattedDate("25-01-2026, 14:30")).toBe(true);
    expect(isStringValidFormattedDate("25-01-2026 14:30")).toBe(true);
  });

  it("should validate Korean format (ko-KR)", () => {
    expect(isStringValidFormattedDate("2026. 01. 11. 14:30")).toBe(true);
  });

  it("should validate French format with à separator", () => {
    expect(isStringValidFormattedDate("11/01/2026 à 14:30")).toBe(true);
  });

  it("should reject completely invalid formats", () => {
    expect(isStringValidFormattedDate("2026")).toBe(false);
    expect(isStringValidFormattedDate("14:30")).toBe(false);
    expect(isStringValidFormattedDate("January 11")).toBe(false);
    expect(isStringValidFormattedDate("11-2026-01")).toBe(false);
  });

  it("should handle whitespace-only strings", () => {
    expect(isStringValidFormattedDate("   ")).toBe(false);
    expect(isStringValidFormattedDate("\t\n")).toBe(false);
  });
});

describe("formatDate - edge cases", () => {
  it("should handle dates at midnight", () => {
    const result = formatDate("2026-01-11T00:00:00.000Z", { utc: true });
    expect(result).toBeTruthy();
    expect(result).toMatch(/00:00|12:00/);
  });

  it("should handle dates at noon", () => {
    const result = formatDate("2026-01-11T12:00:00.000Z", { utc: true });
    expect(result).toBeTruthy();
    expect(result).toMatch(/12:00/);
  });

  it("should handle end of year dates", () => {
    const result = formatDate("2026-12-31T23:59:59.000Z", { utc: true });
    expect(result).toBeTruthy();
    expect(result).toContain("2026");
  });

  it("should handle leap year dates", () => {
    const result = formatDate("2024-02-29T12:00:00.000Z", { utc: true });
    expect(result).toBeTruthy();
    expect(result).toContain("2024");
  });

  it("should return empty string for non-string input", () => {
    // @ts-expect-error - testing invalid input
    expect(formatDate(123)).toBe("");
    // @ts-expect-error - testing invalid input
    expect(formatDate(null)).toBe("");
    // @ts-expect-error - testing invalid input
    expect(formatDate(undefined)).toBe("");
  });

  it("should handle dates without UTC flag (local time)", () => {
    const result = formatDate("2026-01-11T14:30:00.000Z");
    expect(result).toBeTruthy();
    expect(result).toContain("2026");
  });
});
