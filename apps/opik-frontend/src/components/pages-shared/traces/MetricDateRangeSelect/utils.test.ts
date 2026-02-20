import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import dayjs from "dayjs";
import utc from "dayjs/plugin/utc";
import { calculateIntervalStartAndEnd } from "./utils";
import { DateRangeValue } from "@/components/shared/DateRangeSelect";

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
      // Use same instant as mocked "now" so isEndDateToday is true in any timezone
      const today = new Date(mockCurrentDate);
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
      const today = new Date(mockCurrentDate);
      const yesterday = new Date("2024-01-14T12:00:00.000Z");
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
      const today = new Date(mockCurrentDate);
      const threeDaysAgo = new Date("2024-01-12T12:00:00.000Z");
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
      const today = new Date(mockCurrentDate);
      const weekAgo = new Date("2024-01-08T12:00:00.000Z");
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
      const today = new Date(mockCurrentDate);
      const oneDayAgo = new Date("2024-01-14T12:00:00.000Z");
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

    it("should handle Date objects with time components", () => {
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
