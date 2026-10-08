import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import dayjs from "dayjs";
import utc from "dayjs/plugin/utc";
import {
  calculateIntervalBounds,
  calculateIntervalStartAndEnd,
  isLiveDateRange,
  parseDateRangeFromState,
  reanchorIntervalBounds,
} from "./utils";
import {
  DateRangePreset,
  DateRangeValue,
  PRESET_DATE_RANGES,
} from "@/shared/DateRangeSelect";

dayjs.extend(utc);

describe("calculateIntervalStartAndEnd", () => {
  const mockCurrentDate = "2024-01-15T14:30:00.000Z";

  beforeEach(() => {
    // Mock the current time to a fixed point for consistent testing
    vi.useFakeTimers();
    vi.setSystemTime(new Date(mockCurrentDate));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  describe("when end date is today", () => {
    it("should use current time as end and calculate start based on difference for <= 1 day range", () => {
      const today = dayjs(mockCurrentDate).startOf("day").toDate();
      const dateRange: DateRangeValue = {
        from: today,
        to: today, // Same day
      };

      const result = calculateIntervalStartAndEnd(dateRange);

      // Should use current time as end
      expect(result.intervalEnd).toBe(dayjs(mockCurrentDate).utc().format());

      // Should subtract 1 day (since daysDiff = 0, it uses || 1) and start from hour
      const expectedStart = dayjs(mockCurrentDate)
        .utc()
        .subtract(1, "days")
        .startOf("hour");
      expect(result.intervalStart).toBe(expectedStart.format());
    });

    it("should use current time as end and calculate start based on difference for 1 day range", () => {
      const today = dayjs(mockCurrentDate).startOf("day").toDate();
      const yesterday = dayjs(mockCurrentDate)
        .subtract(1, "day")
        .startOf("day")
        .toDate();
      const dateRange: DateRangeValue = {
        from: yesterday,
        to: today,
      };

      const result = calculateIntervalStartAndEnd(dateRange);

      // Should use current time as end
      expect(result.intervalEnd).toBe(dayjs(mockCurrentDate).utc().format());

      // Should subtract 1 day and start from hour (since daysDiff <= 1)
      const expectedStart = dayjs(mockCurrentDate)
        .utc()
        .subtract(1, "days")
        .startOf("hour");
      expect(result.intervalStart).toBe(expectedStart.format());
    });

    it("should use current time as end and calculate start based on difference for > 1 day range", () => {
      const today = dayjs(mockCurrentDate).startOf("day").toDate();
      const threeDaysAgo = dayjs(mockCurrentDate)
        .subtract(3, "days")
        .startOf("day")
        .toDate();
      const dateRange: DateRangeValue = {
        from: threeDaysAgo,
        to: today,
      };

      const result = calculateIntervalStartAndEnd(dateRange);

      // Should use current time as end
      expect(result.intervalEnd).toBe(dayjs(mockCurrentDate).utc().format());

      // Should subtract 3 days and start from day (since daysDiff > 1)
      const expectedStart = dayjs(mockCurrentDate)
        .utc()
        .subtract(3, "days")
        .startOf("day");
      expect(result.intervalStart).toBe(expectedStart.format());
    });

    it("should handle week-long range ending today", () => {
      const today = dayjs(mockCurrentDate).startOf("day").toDate();
      const weekAgo = dayjs(mockCurrentDate)
        .subtract(7, "days")
        .startOf("day")
        .toDate();
      const dateRange: DateRangeValue = {
        from: weekAgo,
        to: today,
      };

      const result = calculateIntervalStartAndEnd(dateRange);

      // Should use current time as end
      expect(result.intervalEnd).toBe(dayjs(mockCurrentDate).utc().format());

      // Should subtract 7 days and start from day
      const expectedStart = dayjs(mockCurrentDate)
        .utc()
        .subtract(7, "days")
        .startOf("day");
      expect(result.intervalStart).toBe(expectedStart.format());
    });
  });

  describe("when end date is not today", () => {
    it("should use end of selected date for <= 1 day range", () => {
      const pastDate = new Date("2024-01-10");
      const dateRange: DateRangeValue = {
        from: pastDate,
        to: pastDate, // Same day
      };

      const result = calculateIntervalStartAndEnd(dateRange);

      // Should use end of the selected date
      const expectedEnd = dayjs(pastDate).utc().endOf("day");
      expect(result.intervalEnd).toBe(expectedEnd.format());

      // Should use start of hour for the from date
      const expectedStart = dayjs(pastDate).utc().startOf("hour");
      expect(result.intervalStart).toBe(expectedStart.format());
    });

    it("should use end of selected date for 1 day range", () => {
      const pastDate = new Date("2024-01-10");
      const dayBefore = new Date("2024-01-09");
      const dateRange: DateRangeValue = {
        from: dayBefore,
        to: pastDate,
      };

      const result = calculateIntervalStartAndEnd(dateRange);

      // Should use end of the selected to date
      const expectedEnd = dayjs(pastDate).utc().endOf("day");
      expect(result.intervalEnd).toBe(expectedEnd.format());

      // Should use start of hour for the from date (since daysDiff <= 1)
      const expectedStart = dayjs(dayBefore).utc().startOf("hour");
      expect(result.intervalStart).toBe(expectedStart.format());
    });

    it("should use end of selected date for > 1 day range", () => {
      const pastDate = new Date("2024-01-10");
      const weekBefore = new Date("2024-01-03");
      const dateRange: DateRangeValue = {
        from: weekBefore,
        to: pastDate,
      };

      const result = calculateIntervalStartAndEnd(dateRange);

      // Should use end of the selected to date
      const expectedEnd = dayjs(pastDate).utc().endOf("day");
      expect(result.intervalEnd).toBe(expectedEnd.format());

      // Should use start of day for the from date (since daysDiff > 1)
      const expectedStart = dayjs(weekBefore).utc().startOf("day");
      expect(result.intervalStart).toBe(expectedStart.format());
    });

    it("should handle future dates", () => {
      const futureDate = new Date("2024-01-20");
      const futureStartDate = new Date("2024-01-18");
      const dateRange: DateRangeValue = {
        from: futureStartDate,
        to: futureDate,
      };

      const result = calculateIntervalStartAndEnd(dateRange);

      // Should use end of the selected to date
      const expectedEnd = dayjs(futureDate).utc().endOf("day");
      expect(result.intervalEnd).toBe(expectedEnd.format());

      // Should use start of day for the from date (since daysDiff = 2 > 1)
      const expectedStart = dayjs(futureStartDate).utc().startOf("day");
      expect(result.intervalStart).toBe(expectedStart.format());
    });
  });

  describe("edge cases", () => {
    it("should handle exact boundary of 1 day difference with today", () => {
      const today = dayjs(mockCurrentDate).startOf("day").toDate();
      const oneDayAgo = dayjs(mockCurrentDate)
        .subtract(1, "day")
        .startOf("day")
        .toDate();
      const dateRange: DateRangeValue = {
        from: oneDayAgo,
        to: today,
      };

      const result = calculateIntervalStartAndEnd(dateRange);

      // Should use current time as end
      expect(result.intervalEnd).toBe(dayjs(mockCurrentDate).utc().format());

      // Should use startOf("hour") since daysDiff = 1 <= 1
      const expectedStart = dayjs(mockCurrentDate)
        .utc()
        .subtract(1, "days")
        .startOf("hour");
      expect(result.intervalStart).toBe(expectedStart.format());
    });

    it("should handle exact boundary of 1 day difference not today", () => {
      const pastDate = new Date("2024-01-10");
      const oneDayBefore = new Date("2024-01-09");
      const dateRange: DateRangeValue = {
        from: oneDayBefore,
        to: pastDate,
      };

      const result = calculateIntervalStartAndEnd(dateRange);

      // Should use end of the selected to date
      const expectedEnd = dayjs(pastDate).utc().endOf("day");
      expect(result.intervalEnd).toBe(expectedEnd.format());

      // Should use startOf("hour") since daysDiff = 1 <= 1
      const expectedStart = dayjs(oneDayBefore).utc().startOf("hour");
      expect(result.intervalStart).toBe(expectedStart.format());
    });

    it("should handle when daysDiff is 0 (same day)", () => {
      const date = new Date("2024-01-10");
      const dateRange: DateRangeValue = {
        from: date,
        to: date,
      };

      const result = calculateIntervalStartAndEnd(dateRange);

      // Since it's not today, should use end of selected date
      const expectedEnd = dayjs(date).utc().endOf("day");
      expect(result.intervalEnd).toBe(expectedEnd.format());

      // Should use startOf("hour") since daysDiff = 0 <= 1
      const expectedStart = dayjs(date).utc().startOf("hour");
      expect(result.intervalStart).toBe(expectedStart.format());
    });

    it("should handle Date objects with time v1", () => {
      const today = new Date("2024-01-15T16:45:30.500Z");
      const yesterday = new Date("2024-01-14T08:20:15.250Z");
      const dateRange: DateRangeValue = {
        from: yesterday,
        to: today,
      };

      const result = calculateIntervalStartAndEnd(dateRange);

      // Should use current time as end (since it's today)
      expect(result.intervalEnd).toBe(dayjs(mockCurrentDate).utc().format());

      // Should subtract 1 day from current time and start from hour
      const expectedStart = dayjs(mockCurrentDate)
        .utc()
        .subtract(1, "days")
        .startOf("hour");
      expect(result.intervalStart).toBe(expectedStart.format());
    });
  });

  describe("output format validation", () => {
    it("should return ISO string format", () => {
      const dateRange: DateRangeValue = {
        from: new Date("2024-01-10"),
        to: new Date("2024-01-10"),
      };

      const result = calculateIntervalStartAndEnd(dateRange);

      // Check that the returned strings are valid ISO format
      expect(() => new Date(result.intervalStart)).not.toThrow();
      const intervalEnd = result.intervalEnd;
      if (intervalEnd) {
        expect(() => new Date(intervalEnd)).not.toThrow();
      }

      // Check that they're valid dayjs ISO format
      expect(dayjs(result.intervalStart).isValid()).toBe(true);
      if (intervalEnd) {
        expect(dayjs(intervalEnd).isValid()).toBe(true);
      }
    });

    it("should always return UTC times", () => {
      const dateRange: DateRangeValue = {
        from: new Date("2024-01-10"),
        to: new Date("2024-01-12"),
      };

      const result = calculateIntervalStartAndEnd(dateRange);

      // Check that times end with 'Z' indicating UTC
      expect(result.intervalStart).toMatch(/Z$/);
      const intervalEnd = result.intervalEnd;
      if (intervalEnd) {
        expect(intervalEnd).toMatch(/Z$/);
      }
    });
  });
});

describe("calculateIntervalBounds", () => {
  const now = dayjs(PRESET_DATE_RANGES.past24hours.to)
    .startOf("day")
    .add(12, "hours")
    .add(30, "minutes");

  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(now.toDate());
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it.each<DateRangePreset>([
    "past24hours",
    "past3days",
    "past7days",
    "past30days",
    "past60days",
  ])(
    "should close the %s preset at the current time and keep its start",
    (preset) => {
      const dateRange = PRESET_DATE_RANGES[preset];
      const openInterval = calculateIntervalStartAndEnd(dateRange);

      const result = calculateIntervalBounds(dateRange);

      expect(openInterval.intervalEnd).toBeUndefined();
      expect(result).toEqual({
        intervalStart: openInterval.intervalStart,
        intervalEnd: now.utc().format(),
      });
    },
  );

  it("should keep a preset relative to the clock after the day it was created", () => {
    const nextDay = now.add(1, "day");
    vi.setSystemTime(nextDay.toDate());

    const result = calculateIntervalBounds(PRESET_DATE_RANGES.past7days);

    expect(result).toEqual({
      intervalStart: nextDay.utc().subtract(6, "days").startOf("day").format(),
      intervalEnd: nextDay.utc().format(),
    });
  });

  it("should give All time an explicit window from five years ago until now", () => {
    const fiveYearsAgo = now.subtract(5, "years").format("YYYY-MM-DD");

    const result = calculateIntervalBounds(PRESET_DATE_RANGES.alltime);

    expect(result).toEqual({
      intervalStart: `${fiveYearsAgo}T00:00:00Z`,
      intervalEnd: now.utc().format(),
    });
  });

  it("should leave a past custom range unchanged", () => {
    const dateRange: DateRangeValue = {
      from: new Date("2024-01-03"),
      to: new Date("2024-01-10"),
    };

    const result = calculateIntervalBounds(dateRange);

    expect(result).toEqual(calculateIntervalStartAndEnd(dateRange));
    expect(result.intervalEnd).toBe(
      dayjs(dateRange.to).utc().endOf("day").format(),
    );
  });

  it("should leave a custom range ending today unchanged", () => {
    const dateRange: DateRangeValue = {
      from: now.subtract(10, "days").startOf("day").toDate(),
      to: now.endOf("day").toDate(),
    };

    const result = calculateIntervalBounds(dateRange);

    expect(result).toEqual(calculateIntervalStartAndEnd(dateRange));
    expect(result.intervalEnd).toBe(now.utc().format());
  });
});

describe("reanchorIntervalBounds", () => {
  const now = dayjs(PRESET_DATE_RANGES.past24hours.to)
    .startOf("day")
    .add(12, "hours")
    .add(30, "minutes");
  const later = now.add(90, "minutes");

  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(now.toDate());
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it.each<DateRangePreset>([
    "past24hours",
    "past3days",
    "past7days",
    "past30days",
    "past60days",
  ])("should move the %s preset to the current time", (preset) => {
    const dateRange = PRESET_DATE_RANGES[preset];
    const bounds = calculateIntervalBounds(dateRange);
    vi.setSystemTime(later.toDate());

    const result = reanchorIntervalBounds(dateRange, bounds);

    expect(result).not.toBe(bounds);
    expect(result).toEqual(calculateIntervalBounds(dateRange));
    expect(result.intervalEnd).toBe(later.utc().format());
  });

  it("should move a custom range ending today to the current time", () => {
    const dateRange: DateRangeValue = {
      from: now.subtract(10, "days").startOf("day").toDate(),
      to: now.endOf("day").toDate(),
    };
    const bounds = calculateIntervalBounds(dateRange);
    vi.setSystemTime(later.toDate());

    const result = reanchorIntervalBounds(dateRange, bounds);

    expect(result.intervalEnd).toBe(later.utc().format());
  });

  it("should keep the bounds of a past custom range", () => {
    const dateRange: DateRangeValue = {
      from: new Date("2024-01-03"),
      to: new Date("2024-01-10"),
    };
    const bounds = calculateIntervalBounds(dateRange);
    vi.setSystemTime(later.toDate());

    expect(reanchorIntervalBounds(dateRange, bounds)).toBe(bounds);
  });

  it("should keep the bounds when refreshed within the same second", () => {
    const dateRange = PRESET_DATE_RANGES.past7days;
    const bounds = calculateIntervalBounds(dateRange);

    expect(reanchorIntervalBounds(dateRange, bounds)).toBe(bounds);
  });
});

describe("isLiveDateRange", () => {
  const now = dayjs(PRESET_DATE_RANGES.past24hours.to)
    .startOf("day")
    .add(12, "hours");
  const nextDay = now.add(1, "day");
  const endingToday: DateRangeValue = {
    from: now.subtract(10, "days").startOf("day").toDate(),
    to: now.endOf("day").toDate(),
  };

  afterEach(() => {
    vi.useRealTimers();
  });

  it.each<[string, DateRangeValue, dayjs.Dayjs, boolean]>([
    ["a preset on its own day", PRESET_DATE_RANGES.past7days, now, true],
    ["a preset after its day", PRESET_DATE_RANGES.past7days, nextDay, true],
    ["a custom range ending today", endingToday, now, true],
    ["a custom range that ended yesterday", endingToday, nextDay, false],
  ])("should tell whether %s is live", (_, dateRange, time, expected) => {
    vi.useFakeTimers();
    vi.setSystemTime(time.toDate());

    expect(isLiveDateRange(dateRange)).toBe(expected);
  });
});

describe("parseDateRangeFromState", () => {
  const minDate = new Date(2025, 0, 1);
  const maxDate = new Date(2026, 11, 31);
  const fallback = PRESET_DATE_RANGES.past30days;

  it.each<[string, string]>([
    ["a start after the end", "2026-10-07,2026-10-06"],
    ["an impossible start date", "2026-13-45,2026-10-06"],
    ["an impossible end date", "2026-10-01,2026-02-30"],
  ])("should fall back to the default preset for %s", (_, value) => {
    expect(
      parseDateRangeFromState(value, minDate, maxDate, "past30days"),
    ).toEqual(fallback);
  });

  it.each<[string, string, Date, Date]>([
    [
      "a range of several days",
      "2026-10-01,2026-10-06",
      new Date(2026, 9, 1),
      new Date(2026, 9, 6),
    ],
    [
      "a single day",
      "2026-10-06,2026-10-06",
      new Date(2026, 9, 6),
      new Date(2026, 9, 6),
    ],
  ])("should keep %s", (_, value, from, to) => {
    expect(
      parseDateRangeFromState(value, minDate, maxDate, "past30days"),
    ).toEqual({ from, to });
  });
});
