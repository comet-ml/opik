import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { millisecondsToSeconds, formatDate } from "./date";

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

  // Helper to mock navigator.language
  const mockLocale = (locale: string) => {
    vi.stubGlobal("navigator", { language: locale });
  };

  beforeEach(() => {
    vi.unstubAllGlobals();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("should return empty string for invalid date", () => {
    mockLocale("en-US");
    expect(formatDate("invalid")).toBe("");
    expect(formatDate("")).toBe("");
  });

  it("should format date in US locale (en-US)", () => {
    mockLocale("en-US");
    const result = formatDate(testDate, { utc: true });
    // en-US format: M/D/YYYY or MM/DD/YYYY, h:mm AM/PM or hh:mm AM/PM
    // The exact format depends on the Intl implementation
    expect(result).toMatch(/0?1\/11\/2026,?\s*0?2:30\s*PM/i);
  });

  it("should format date in UK locale (en-GB)", () => {
    mockLocale("en-GB");
    const result = formatDate(testDate, { utc: true });
    // en-GB format: DD/MM/YYYY, HH:mm
    expect(result).toMatch(/11\/01\/2026,?\s*14:30/);
  });

  it("should format date in German locale (de-DE)", () => {
    mockLocale("de-DE");
    const result = formatDate(testDate, { utc: true });
    // de-DE format: DD.MM.YYYY, HH:mm
    expect(result).toMatch(/11\.01\.2026,?\s*14:30/);
  });

  it("should format date in Japanese locale (ja-JP)", () => {
    mockLocale("ja-JP");
    const result = formatDate(testDate, { utc: true });
    // ja-JP format: YYYY/MM/DD HH:mm
    expect(result).toMatch(/2026\/01\/11\s*14:30/);
  });

  it("should include seconds when includeSeconds is true", () => {
    mockLocale("en-US");
    const result = formatDate(testDate, { utc: true, includeSeconds: true });
    // Should include seconds in the output
    expect(result).toMatch(/0?1\/11\/2026,?\s*0?2:30:00\s*PM/i);
  });

  it("should handle UTC option correctly", () => {
    mockLocale("en-US");
    // When utc is true, should show UTC time (14:30)
    const utcResult = formatDate(testDate, { utc: true });
    expect(utcResult).toMatch(/2:30\s*PM/i);
  });

  it("should fallback gracefully when navigator is undefined", () => {
    vi.stubGlobal("navigator", undefined);
    // Should not throw and should return a formatted date
    const result = formatDate(testDate, { utc: true });
    expect(result).toBeTruthy();
    expect(result.length).toBeGreaterThan(0);
  });
});
