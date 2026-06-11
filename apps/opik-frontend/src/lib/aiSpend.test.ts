import { describe, expect, it } from "vitest";
import {
  formatSpendCount,
  formatSpendUsd,
  getSpendTrendPercentage,
  isAiSpendRoute,
} from "./aiSpend";

describe("getSpendTrendPercentage", () => {
  it("returns undefined when either side is null", () => {
    expect(
      getSpendTrendPercentage({ current: null, previous: 10 }),
    ).toBeUndefined();
    expect(
      getSpendTrendPercentage({ current: 10, previous: null }),
    ).toBeUndefined();
  });

  it("returns 0 when both values are 0", () => {
    expect(getSpendTrendPercentage({ current: 0, previous: 0 })).toBe(0);
  });

  it("returns Infinity when growing from 0 to a positive value", () => {
    expect(getSpendTrendPercentage({ current: 5, previous: 0 })).toBe(Infinity);
  });

  it("computes the percentage change otherwise", () => {
    expect(getSpendTrendPercentage({ current: 150, previous: 100 })).toBe(50);
    expect(getSpendTrendPercentage({ current: 50, previous: 100 })).toBe(-50);
  });
});

describe("isAiSpendRoute", () => {
  it("matches the ai-spend area and its subroutes", () => {
    expect(isAiSpendRoute("/ws/ai-spend")).toBe(true);
    expect(isAiSpendRoute("/ws/ai-spend/")).toBe(true);
    expect(isAiSpendRoute("/ws/ai-spend/leaderboard")).toBe(true);
  });

  it("does not match unrelated routes", () => {
    expect(isAiSpendRoute("/ws/home")).toBe(false);
    expect(isAiSpendRoute("/ws/ai-spendx")).toBe(false);
  });
});

describe("spend formatters", () => {
  it("formats USD with two decimals and N/A for null", () => {
    expect(formatSpendUsd(1234.5)).toBe("$1,234.50");
    expect(formatSpendUsd(null)).toBe("N/A");
  });

  it("formats counts with grouping and N/A for null", () => {
    expect(formatSpendCount(1234)).toBe("1,234");
    expect(formatSpendCount(null)).toBe("N/A");
  });
});
