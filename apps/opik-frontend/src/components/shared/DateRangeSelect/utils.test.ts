import { describe, expect, it, vi } from "vitest";
import dayjs from "dayjs";
import { customDayClick } from "./utils";
import { DateRange } from "react-day-picker";

describe("customDayClick", () => {
  const mockOnSuccess = vi.fn();

  beforeEach(() => {
    mockOnSuccess.mockClear();
  });

  describe("starting a new selection", () => {
    it("should start new range when no previous selection exists", () => {
      const day = new Date("2024-01-15");
      const prev: DateRange | undefined = undefined;

      const result = customDayClick(prev, day, mockOnSuccess);

      expect(result).toEqual({
        from: day,
        to: undefined,
      });
      expect(mockOnSuccess).not.toHaveBeenCalled();
    });

    it("should start new range when clicking after complete range exists", () => {
      const day = new Date("2024-01-15");
      const prev: DateRange = {
        from: new Date("2024-01-10"),
        to: new Date("2024-01-12"),
      };

      const result = customDayClick(prev, day, mockOnSuccess);

      expect(result).toEqual({
        from: day,
        to: undefined,
      });
      expect(mockOnSuccess).not.toHaveBeenCalled();
    });
  });

  describe("completing a range selection", () => {
    it("should complete range when clicking after start date", () => {
      const startDate = new Date("2024-01-10");
      const endDate = new Date("2024-01-15");
      const prev: DateRange = {
        from: startDate,
        to: undefined,
      };

      const result = customDayClick(prev, endDate, mockOnSuccess);

      expect(result).toEqual({
        from: startDate,
        to: endDate,
      });

      expect(mockOnSuccess).toHaveBeenCalledWith({
        from: startDate,
        to: dayjs(endDate).endOf("day").toDate(),
      });
      expect(mockOnSuccess).toHaveBeenCalledTimes(1);
    });

    it("should complete range when clicking on same date as start", () => {
      const date = new Date("2024-01-10");
      const prev: DateRange = {
        from: date,
        to: undefined,
      };

      const result = customDayClick(prev, date, mockOnSuccess);

      expect(result).toEqual({
        from: date,
        to: date,
      });

      expect(mockOnSuccess).toHaveBeenCalledWith({
        from: date,
        to: dayjs(date).endOf("day").toDate(),
      });
      expect(mockOnSuccess).toHaveBeenCalledTimes(1);
    });
  });

  describe("restarting selection when clicking before start date", () => {
    it("should restart range when clicking before existing start date", () => {
      const startDate = new Date("2024-01-10");
      const earlierDate = new Date("2024-01-05");
      const prev: DateRange = {
        from: startDate,
        to: undefined,
      };

      const result = customDayClick(prev, earlierDate, mockOnSuccess);

      expect(result).toEqual({
        from: earlierDate,
        to: undefined,
      });
      expect(mockOnSuccess).not.toHaveBeenCalled();
    });
  });

  describe("edge cases", () => {
    it("should handle Date objects with time components", () => {
      const startDate = new Date("2024-01-10T08:30:00.000Z");
      const endDate = new Date("2024-01-15T14:45:30.500Z");
      const prev: DateRange = {
        from: startDate,
        to: undefined,
      };

      const result = customDayClick(prev, endDate, mockOnSuccess);

      expect(result).toEqual({
        from: startDate,
        to: endDate,
      });
      expect(mockOnSuccess).toHaveBeenCalledWith({
        from: startDate,
        to: dayjs(endDate).endOf("day").toDate(),
      });
    });

    it("should handle prev with only 'from' property", () => {
      const startDate = new Date("2024-01-10");
      const endDate = new Date("2024-01-15");
      const prev: DateRange = {
        from: startDate,
        // to is implicitly undefined
      };

      const result = customDayClick(prev, endDate, mockOnSuccess);

      expect(result).toEqual({
        from: startDate,
        to: endDate,
      });
      expect(mockOnSuccess).toHaveBeenCalledTimes(1);
    });
  });
});
