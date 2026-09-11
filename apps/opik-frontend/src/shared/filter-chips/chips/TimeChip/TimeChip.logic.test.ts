import { describe, expect, it } from "vitest";
import {
  formatTimeSummary,
  isTimeApplied,
  parseDateInput,
  parseTimeInput,
  timeToFilters,
} from "./TimeChip.logic";
import { TimeChipDefinition } from "@/shared/filter-chips/types";
import { COLUMN_TYPE } from "@/types/shared";

const def: TimeChipDefinition = {
  id: "start_time",
  field: "start_time",
  label: "Start time",
  kind: "time",
  columnType: COLUMN_TYPE.time,
};

const ISO_1 = "2026-05-01T10:30:00.000Z";
const ISO_2 = "2026-05-15T18:45:00.000Z";

describe("isTimeApplied", () => {
  it("returns false when value is undefined", () => {
    expect(isTimeApplied(undefined)).toBe(false);
  });

  it("returns true for exactly mode when 'at' is set", () => {
    expect(isTimeApplied({ mode: "exactly", at: ISO_1 })).toBe(true);
  });

  it("returns true for between mode when both bounds are set", () => {
    expect(isTimeApplied({ mode: "between", start: ISO_1, end: ISO_2 })).toBe(
      true,
    );
  });

  it("returns true for before and after modes", () => {
    expect(isTimeApplied({ mode: "before", before: ISO_1 })).toBe(true);
    expect(isTimeApplied({ mode: "after", after: ISO_1 })).toBe(true);
  });
});

describe("timeToFilters", () => {
  it("emits a >= start AND <= start+1min window for exactly mode", () => {
    const result = timeToFilters({ mode: "exactly", at: ISO_1 }, def);
    expect(result).toHaveLength(2);
    expect(result[0]).toMatchObject({ operator: ">=", value: ISO_1 });
    expect(result[1].operator).toBe("<=");
    // end value should be one minute after `at`
    expect(new Date(result[1].value as string).getTime()).toBe(
      new Date(ISO_1).getTime() + 60_000,
    );
  });

  it("emits >= start AND <= end for between mode", () => {
    const result = timeToFilters(
      { mode: "between", start: ISO_1, end: ISO_2 },
      def,
    );
    expect(result).toHaveLength(2);
    expect(result[0]).toMatchObject({ operator: ">=", value: ISO_1 });
    expect(result[1]).toMatchObject({ operator: "<=", value: ISO_2 });
  });

  it("emits < value for before mode", () => {
    const result = timeToFilters({ mode: "before", before: ISO_1 }, def);
    expect(result).toHaveLength(1);
    expect(result[0]).toMatchObject({ operator: "<", value: ISO_1 });
  });

  it("emits > value for after mode", () => {
    const result = timeToFilters({ mode: "after", after: ISO_1 }, def);
    expect(result).toHaveLength(1);
    expect(result[0]).toMatchObject({ operator: ">", value: ISO_1 });
  });

  it("returns empty for undefined input", () => {
    expect(timeToFilters(undefined, def)).toEqual([]);
  });
});

describe("formatTimeSummary", () => {
  it("returns null when not applied", () => {
    expect(formatTimeSummary(undefined)).toBeNull();
  });

  it("formats exactly mode with '=' prefix", () => {
    expect(formatTimeSummary({ mode: "exactly", at: ISO_1 })).toMatch(
      /^= \d{4}\/\d{2}\/\d{2} \d{1,2}:\d{2} (AM|PM)$/,
    );
  });

  it("formats between mode as date range without times", () => {
    const result = formatTimeSummary({
      mode: "between",
      start: ISO_1,
      end: ISO_2,
    });
    expect(result).toMatch(/^\d{4}\/\d{2}\/\d{2} – \d{4}\/\d{2}\/\d{2}$/);
  });

  it("formats before/after with '<' / '>' prefixes", () => {
    expect(formatTimeSummary({ mode: "before", before: ISO_1 })).toMatch(
      /^< \d{4}\/\d{2}\/\d{2} \d{1,2}:\d{2} (AM|PM)$/,
    );
    expect(formatTimeSummary({ mode: "after", after: ISO_1 })).toMatch(
      /^> \d{4}\/\d{2}\/\d{2} \d{1,2}:\d{2} (AM|PM)$/,
    );
  });
});

describe("parseTimeInput", () => {
  it("parses bare hour as 24h", () => {
    expect(parseTimeInput("7")).toEqual({ hour: 7, minute: 0 });
    expect(parseTimeInput("19")).toEqual({ hour: 19, minute: 0 });
  });

  it("parses 3-4 bare digits as HHMM / HMM", () => {
    expect(parseTimeInput("730")).toEqual({ hour: 7, minute: 30 });
    expect(parseTimeInput("0730")).toEqual({ hour: 7, minute: 30 });
    expect(parseTimeInput("1930")).toEqual({ hour: 19, minute: 30 });
  });

  it("parses H:MM and HH:MM", () => {
    expect(parseTimeInput("7:30")).toEqual({ hour: 7, minute: 30 });
    expect(parseTimeInput("19:30")).toEqual({ hour: 19, minute: 30 });
  });

  it("parses AM/PM suffixes", () => {
    expect(parseTimeInput("7pm")).toEqual({ hour: 19, minute: 0 });
    expect(parseTimeInput("7:30pm")).toEqual({ hour: 19, minute: 30 });
    expect(parseTimeInput("12am")).toEqual({ hour: 0, minute: 0 });
    expect(parseTimeInput("12pm")).toEqual({ hour: 12, minute: 0 });
    expect(parseTimeInput("7a")).toEqual({ hour: 7, minute: 0 });
    expect(parseTimeInput("7p")).toEqual({ hour: 19, minute: 0 });
  });

  it("rejects invalid input", () => {
    expect(parseTimeInput("")).toBeNull();
    expect(parseTimeInput("25:00")).toBeNull();
    expect(parseTimeInput("7:89")).toBeNull();
    expect(parseTimeInput("foo")).toBeNull();
    expect(parseTimeInput("13pm")).toBeNull();
  });
});

describe("parseDateInput", () => {
  const expectYMD = (
    d: Date | null,
    year: number,
    monthIndex: number,
    day: number,
  ) => {
    expect(d).toBeInstanceOf(Date);
    expect(d?.getFullYear()).toBe(year);
    expect(d?.getMonth()).toBe(monthIndex);
    expect(d?.getDate()).toBe(day);
  };

  it("returns null for empty or whitespace input", () => {
    expect(parseDateInput("")).toBeNull();
    expect(parseDateInput("   ")).toBeNull();
  });

  it("accepts the canonical YYYY/MM/DD", () => {
    expectYMD(parseDateInput("2026/05/26"), 2026, 4, 26);
  });

  it("accepts dash and dot separators", () => {
    expectYMD(parseDateInput("2026-05-26"), 2026, 4, 26);
    expectYMD(parseDateInput("2026.05.26"), 2026, 4, 26);
  });

  it("accepts compact YYYYMMDD", () => {
    expectYMD(parseDateInput("20260526"), 2026, 4, 26);
  });

  it("accepts non-zero-padded month and day", () => {
    expectYMD(parseDateInput("2026/5/26"), 2026, 4, 26);
    expectYMD(parseDateInput("2026-5-7"), 2026, 4, 7);
    expectYMD(parseDateInput("2026.5.7"), 2026, 4, 7);
  });

  it("rejects garbage and ambiguous month-first formats", () => {
    expect(parseDateInput("garbage")).toBeNull();
    expect(parseDateInput("5/26/2026")).toBeNull();
    expect(parseDateInput("26/05/2026")).toBeNull();
  });

  it("rejects out-of-range month and day", () => {
    expect(parseDateInput("2026/13/26")).toBeNull();
    expect(parseDateInput("2026/05/32")).toBeNull();
  });
});
