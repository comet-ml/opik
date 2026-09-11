import { describe, expect, it } from "vitest";
import {
  formatDuration,
  formatLocalTimeAsUtc,
  formatUtcTimeAsLocal,
  millisecondsToSeconds,
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

describe("formatLocalTimeAsUtc / formatUtcTimeAsLocal roundtrip", () => {
  it("should roundtrip a time value back to the original", () => {
    const local = "14:30:00";
    const utc = formatLocalTimeAsUtc(local);
    const backToLocal = formatUtcTimeAsLocal(utc);
    expect(backToLocal).toBe("2:30 PM");
  });

  it("should produce HH:mm:ss format for UTC output", () => {
    const result = formatLocalTimeAsUtc("07:00:00");
    expect(result).toMatch(/^\d{2}:\d{2}:\d{2}$/);
  });

  it("should produce h:mm A format for local output", () => {
    const result = formatUtcTimeAsLocal("12:00:00");
    expect(result).toMatch(/^\d{1,2}:\d{2}\s(AM|PM)$/);
  });
});

describe("formatDuration", () => {
  it("should return NA for missing or non-finite values", () => {
    expect(formatDuration(null)).toBe("NA");
    expect(formatDuration(undefined)).toBe("NA");
    expect(formatDuration(NaN)).toBe("NA");
  });

  it("should return rounded seconds by default", () => {
    expect(formatDuration(57600)).toBe("57.6s");
  });

  it("should not leak float error when the remainder is under a minute", () => {
    expect(formatDuration(3615300, false)).toBe("1h 15.3s");
    expect(formatDuration(7201800, false)).toBe("2h 1.8s");
    expect(formatDuration(86400500, false)).toBe("1d 0.5s");
  });

  it("should not leak float error after a week, month or year", () => {
    expect(formatDuration(604800300, false)).toBe("1w 0.3s");
    expect(formatDuration(2592000300, false)).toBe("1mth 0.3s");
    expect(formatDuration(31536000300, false)).toBe("1y 0.3s");
  });

  it("should keep the minute breakdown intact", () => {
    expect(formatDuration(3675300, false)).toBe("1h 1m 15.3s");
    expect(formatDuration(1499000, false)).toBe("24m 59s");
    expect(formatDuration(90061300, false)).toBe("1d 1h 1m 1.3s");
  });

  it("should omit an empty seconds part", () => {
    expect(formatDuration(3600000, false)).toBe("1h");
    expect(formatDuration(0, false)).toBe("0s");
  });

  it("should keep sub-second precision", () => {
    expect(formatDuration(5, false)).toBe("0.005s");
    expect(formatDuration(50, false)).toBe("0.05s");
  });
});
