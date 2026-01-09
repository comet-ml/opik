import { describe, expect, it, vi } from "vitest";
import { renderHook } from "@testing-library/react";
import useChartTickDefaultConfig from "./useChartTickDefaultConfig";

// Mock getTextWidth utility to avoid DOM dependencies
vi.mock("@/lib/utils", () => ({
  getTextWidth: vi.fn((texts: string[]) => {
    // Mock text width calculation - approximate based on character count
    return texts.map((text) => text.length * 8); // ~8px per character
  }),
}));

describe("useChartTickDefaultConfig", () => {
  describe("basic functionality", () => {
    it("should return default configuration for empty values", () => {
      const { result } = renderHook(() => useChartTickDefaultConfig([]));

      expect(result.current.ticks).toEqual([]);
      expect(result.current.domain).toEqual([0, "max"]);
      expect(result.current.interval).toBe("preserveStartEnd");
    });

    it("should filter out null values", () => {
      const { result } = renderHook(() =>
        useChartTickDefaultConfig([1, null, 2, null, 3]),
      );

      expect(result.current.ticks.length).toBeGreaterThan(0);
      // Should generate nice rounded ticks
      expect(result.current.ticks.every((tick) => tick !== null)).toBe(true);
      // With nice numbers, expect clean values like 0, 1, 2, 3, 4
      expect(result.current.ticks).toContain(0);
    });
  });

  describe("integer values", () => {
    it("should generate nice rounded ticks for integer values", () => {
      const { result } = renderHook(() =>
        useChartTickDefaultConfig([0, 10, 20, 30, 40]),
      );

      expect(result.current.ticks.length).toBeGreaterThan(0);
      // With nice numbers, should generate round values
      expect(result.current.ticks.every((t) => t % 10 === 0 || t === 0)).toBe(
        true,
      );
      // Check that formatter doesn't add decimals to integers
      const formatted = result.current.yTickFormatter(10);
      expect(formatted).toBe("10");
    });

    it("should generate nice ticks for 0-1 range", () => {
      const { result } = renderHook(() =>
        useChartTickDefaultConfig([0, 0.3, 0.5, 0.8]),
      );

      expect(result.current.ticks.length).toBeGreaterThan(0);
      // Should generate nice decimals like 0, 0.2, 0.4, 0.6, 0.8, 1.0
      // Check that values are "nice" (multiples of 0.2 or similar)
      const hasNiceValues = result.current.ticks.some(
        (t) => t === 0 || t === 0.2 || t === 0.5 || t === 1,
      );
      expect(hasNiceValues).toBe(true);
    });
  });

  describe("decimal values", () => {
    it("should generate nice tick values for decimal ranges", () => {
      const { result } = renderHook(() =>
        useChartTickDefaultConfig([1.5, 2.3, 3.8, 4.2]),
      );

      expect(result.current.ticks.length).toBeGreaterThan(0);
      // Should generate nice numbers (not 1.5, 2.3, etc.)
      // Likely: 0, 1, 2, 3, 4, 5 or similar
      const hasNiceValues = result.current.ticks.some(
        (t) => Number.isInteger(t) || t % 0.5 === 0,
      );
      expect(hasNiceValues).toBe(true);
    });

    it("should handle mixed integer and decimal values with nice numbers", () => {
      const { result } = renderHook(() =>
        useChartTickDefaultConfig([0, 1.5, 3, 4.5, 6]),
      );

      expect(result.current.ticks.length).toBeGreaterThan(0);
      // Should generate clean tick values
      expect(result.current.ticks[0]).toBe(0);
      // Integers should not have decimals
      expect(result.current.yTickFormatter(3)).toBe("3");
      // Decimals should be formatted properly
      expect(result.current.yTickFormatter(4.5)).toBe("4.5");
    });
  });

  describe("small decimal values", () => {
    it("should generate nice tick values for very small ranges", () => {
      const { result } = renderHook(() =>
        useChartTickDefaultConfig([0.001, 0.002, 0.003, 0.004]),
      );

      expect(result.current.ticks.length).toBeGreaterThan(0);
      // Should generate nice small decimals
      expect(result.current.ticks[0]).toBe(0);
      // Should format with adequate precision
      const formatted = result.current.yTickFormatter(0.002);
      expect(formatted).toBe("0.002");
    });

    it("should handle extremely small values with capped precision", () => {
      const { result } = renderHook(() =>
        useChartTickDefaultConfig([0.0000001, 0.0000002, 0.0000003]),
      );

      expect(result.current.ticks.length).toBeGreaterThan(0);
      // Should still generate some ticks
      expect(result.current.ticks[0]).toBe(0);
      // Precision should be capped at 6
      const formatted = result.current.yTickFormatter(0.0000002);
      expect(formatted.split(".")[1]?.length || 0).toBeLessThanOrEqual(6);
    });
  });

  describe("nice number generation", () => {
    it("should generate nice tick values for 0-1 range", () => {
      const { result } = renderHook(() =>
        useChartTickDefaultConfig([0, 0.2, 0.5, 0.8, 1.0]),
      );

      expect(result.current.ticks.length).toBeGreaterThan(0);
      // Should not have ugly values like 0.3333 or 0.6667
      const hasUglyValues = result.current.ticks.some((t) => {
        const str = t.toString();
        return str.includes("333") || str.includes("667");
      });
      expect(hasUglyValues).toBe(false);
    });

    it("should generate nice tick values for 0-100 range", () => {
      const { result } = renderHook(() =>
        useChartTickDefaultConfig([0, 25, 50, 75, 100]),
      );

      expect(result.current.ticks.length).toBeGreaterThan(0);
      // Should generate round numbers like 0, 20, 40, 60, 80, 100
      const allNice = result.current.ticks.every(
        (t) => t % 10 === 0 || t % 20 === 0 || t % 25 === 0 || t % 50 === 0,
      );
      expect(allNice).toBe(true);
    });

    it("should generate nice tick values with 2.5 multiplier", () => {
      const { result } = renderHook(() =>
        useChartTickDefaultConfig([0, 3, 7, 9]),
      );

      expect(result.current.ticks.length).toBeGreaterThan(0);
      // With range 0-9, might use step of 2.5 or 2
      // Should generate clean values
      expect(result.current.ticks[0]).toBe(0);
      const hasNiceStep = result.current.ticks.some(
        (t) => t === 2.5 || t === 5 || t === 2,
      );
      expect(hasNiceStep).toBe(true);
    });

    it("should avoid floating-point precision errors", () => {
      const { result } = renderHook(() =>
        useChartTickDefaultConfig([0, 0.1, 0.2, 0.3]),
      );

      expect(result.current.ticks.length).toBeGreaterThan(0);
      // Check no values like 0.30000000000000004
      const hasCleanValues = result.current.ticks.every((t) => {
        const str = t.toString();
        return !str.includes("00000");
      });
      expect(hasCleanValues).toBe(true);
    });
  });

  describe("configuration options", () => {
    it("should respect custom targetTickCount", () => {
      const { result } = renderHook(() =>
        useChartTickDefaultConfig([0, 10, 20, 30], { targetTickCount: 3 }),
      );

      expect(result.current.ticks.length).toBeLessThanOrEqual(3);
    });

    it("should respect maxTickPrecision", () => {
      const { result } = renderHook(() =>
        useChartTickDefaultConfig([0.123456789], { maxTickPrecision: 3 }),
      );

      const formatted = result.current.yTickFormatter(0.123456789);
      const decimals = formatted.split(".")[1]?.length || 0;
      expect(decimals).toBeLessThanOrEqual(3);
    });

    it("should use custom tickFormatter if provided", () => {
      const customFormatter = (value: number) => `$${value.toFixed(2)}`;
      const { result } = renderHook(() =>
        useChartTickDefaultConfig([10, 20, 30], {
          tickFormatter: customFormatter,
        }),
      );

      expect(result.current.yTickFormatter(25)).toBe("$25.00");
    });
  });

  describe("domain and ticks with showMinMaxDomain", () => {
    it("should calculate numeric domain with padding when showMinMaxDomain is true", () => {
      const { result } = renderHook(() =>
        useChartTickDefaultConfig([10, 20, 30, 40], { showMinMaxDomain: true }),
      );

      expect(Array.isArray(result.current.domain)).toBe(true);
      expect(result.current.domain.length).toBe(2);
      // Domain should include padding
      const [min, max] = result.current.domain as [number, number];
      expect(min).toBeLessThan(10);
      expect(max).toBeGreaterThan(40);
    });

    it("should generate evenly spaced ticks within domain", () => {
      const { result } = renderHook(() =>
        useChartTickDefaultConfig([10, 20, 30], {
          showMinMaxDomain: true,
          targetTickCount: 5,
        }),
      );

      expect(result.current.ticks.length).toBeGreaterThan(0);
      // Ticks should be sorted
      const sortedTicks = [...result.current.ticks].sort((a, b) => a - b);
      expect(result.current.ticks).toEqual(sortedTicks);
    });

    it("should use default domain [0, 'max'] when showMinMaxDomain is false", () => {
      const { result } = renderHook(() =>
        useChartTickDefaultConfig([10, 20, 30], { showMinMaxDomain: false }),
      );

      expect(result.current.domain).toEqual([0, "max"]);
    });
  });

  describe("width calculation", () => {
    it("should calculate appropriate width based on tick labels", () => {
      const { result } = renderHook(() =>
        useChartTickDefaultConfig([0, 100, 200]),
      );

      expect(result.current.width).toBeGreaterThanOrEqual(26); // MIN_Y_AXIS_WIDTH
      expect(result.current.width).toBeLessThanOrEqual(80); // MAX_Y_AXIS_WIDTH
    });

    it("should return minimum width for empty values", () => {
      const { result } = renderHook(() => useChartTickDefaultConfig([]));

      expect(result.current.width).toBe(26); // MIN_Y_AXIS_WIDTH
    });
  });

  describe("edge cases", () => {
    it("should handle all null values", () => {
      const { result } = renderHook(() =>
        useChartTickDefaultConfig([null, null, null]),
      );

      expect(result.current.ticks).toEqual([]);
      expect(result.current.domain).toEqual([0, "max"]);
    });

    it("should handle single value", () => {
      const { result } = renderHook(() => useChartTickDefaultConfig([5]));

      expect(result.current.ticks.length).toBeGreaterThan(0);
      expect(result.current.domain).toEqual([0, "max"]);
    });

    it("should handle all same values", () => {
      const { result } = renderHook(() =>
        useChartTickDefaultConfig([10, 10, 10, 10]),
      );

      expect(result.current.ticks.length).toBeGreaterThan(0);
    });

    it("should handle negative values", () => {
      const { result } = renderHook(() =>
        useChartTickDefaultConfig([-10, -5, 0, 5, 10]),
      );

      expect(result.current.ticks.length).toBeGreaterThan(0);
      expect(result.current.yTickFormatter(-5)).toBe("-5");
    });

    it("should handle very large values", () => {
      const { result } = renderHook(() =>
        useChartTickDefaultConfig([1000000, 2000000, 3000000]),
      );

      expect(result.current.ticks.length).toBeGreaterThan(0);
      expect(result.current.yTickFormatter(2000000)).toBe("2000000");
    });
  });

  describe("critical bug tests - zero range scenarios", () => {
    it("should handle zero range - all identical values", () => {
      const { result } = renderHook(() =>
        useChartTickDefaultConfig([5, 5, 5, 5]),
      );

      // Should not crash
      expect(result.current.ticks).toBeDefined();
      // Should have valid ticks (not NaN or Infinity)
      expect(result.current.ticks.every((tick) => Number.isFinite(tick))).toBe(
        true,
      );
      // Ticks should not be empty
      expect(result.current.ticks.length).toBeGreaterThan(0);
      // When using default domain [0, "max"], ticks will include 0 and go up
      // The important thing is no crash and valid numbers
      expect(result.current.ticks[0]).toBe(0);
      // Max tick should be near the data value (5)
      const maxTick = Math.max(...result.current.ticks);
      expect(maxTick).toBeGreaterThanOrEqual(4);
      expect(maxTick).toBeLessThanOrEqual(6);
    });

    it("should handle zero range - all zeros", () => {
      const { result } = renderHook(() =>
        useChartTickDefaultConfig([0, 0, 0, 0]),
      );

      expect(result.current.ticks).toBeDefined();
      expect(result.current.ticks.every((tick) => Number.isFinite(tick))).toBe(
        true,
      );
      expect(result.current.ticks).toContain(0);
      expect(result.current.ticks.length).toBeGreaterThan(0);
    });

    it("should handle zero range with showMinMaxDomain", () => {
      const { result } = renderHook(() =>
        useChartTickDefaultConfig([10, 10, 10], { showMinMaxDomain: true }),
      );

      expect(result.current.ticks).toBeDefined();
      expect(result.current.ticks.every((tick) => Number.isFinite(tick))).toBe(
        true,
      );
      expect(result.current.ticks.length).toBeGreaterThan(0);
      // Domain should handle zero range gracefully
      expect(result.current.domain).toBeDefined();
    });

    it("should handle very small range that rounds to zero", () => {
      const { result } = renderHook(() =>
        useChartTickDefaultConfig([1.0000001, 1.0000002, 1.0000003]),
      );

      expect(result.current.ticks).toBeDefined();
      expect(result.current.ticks.every((tick) => Number.isFinite(tick))).toBe(
        true,
      );
      expect(result.current.ticks.length).toBeGreaterThan(0);
    });

    it("should handle negative identical values", () => {
      const { result } = renderHook(() =>
        useChartTickDefaultConfig([-5, -5, -5]),
      );

      expect(result.current.ticks).toBeDefined();
      expect(result.current.ticks.every((tick) => Number.isFinite(tick))).toBe(
        true,
      );
      expect(result.current.ticks).toContain(-5);
    });

    it("should handle negative-only dataset with showMinMaxDomain", () => {
      const { result } = renderHook(() =>
        useChartTickDefaultConfig([-10, -5, -3], { showMinMaxDomain: true }),
      );

      expect(result.current.ticks).toBeDefined();
      expect(result.current.ticks.length).toBeGreaterThan(0);
      expect(result.current.ticks.every((tick) => Number.isFinite(tick))).toBe(
        true,
      );
      // Domain should allow negative values
      const [domainMin, domainMax] = result.current.domain as [number, number];
      expect(domainMin).toBeLessThan(0);
      expect(domainMax).toBeLessThan(0);
      // Ticks should be in the negative range
      expect(result.current.ticks.every((tick) => tick <= 0)).toBe(true);
    });
  });

  describe("precision consistency tests", () => {
    it("should handle data with high precision but generate nice ticks", () => {
      const { result } = renderHook(() =>
        useChartTickDefaultConfig([0.123456, 0.234567, 0.345678]),
      );

      expect(result.current.ticks).toBeDefined();
      expect(result.current.ticks.length).toBeGreaterThan(0);
      // Ticks should be nice numbers (like 0, 0.1, 0.2, 0.3, 0.4)
      // Not ugly numbers like 0.123456
      const hasNiceNumbers = result.current.ticks.some(
        (t) =>
          t === 0 ||
          t === 0.1 ||
          t === 0.2 ||
          t === 0.3 ||
          t === 0.4 ||
          t === 0.5,
      );
      expect(hasNiceNumbers).toBe(true);
    });

    it("should format ticks consistently with their precision", () => {
      const { result } = renderHook(() =>
        useChartTickDefaultConfig([0, 0.5, 1.0, 1.5, 2.0]),
      );

      // Check that formatter doesn't add unnecessary trailing zeros
      const formatted = result.current.ticks.map((tick) =>
        result.current.yTickFormatter(tick),
      );

      // Should be clean: "0", "0.5", "1", "1.5", "2"
      // Not: "0.0000", "0.5000", etc.
      expect(formatted.every((f) => !f.includes("000"))).toBe(true);
    });
  });
});
