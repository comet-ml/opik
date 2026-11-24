import { describe, expect, it } from "vitest";
import { formatExactCost } from "./money";

describe("formatExactCost", () => {
  it("shows tiny costs in full, never as <$0.01 or e-notation", () => {
    expect(formatExactCost(0.0000114)).toBe("$0.0000114");
    expect(formatExactCost(6e-7)).toBe("$0.0000006");
    expect(formatExactCost(90 * 0.12 * 1e-6)).toBe("$0.0000108");
  });

  it("keeps six significant digits and at least two decimals", () => {
    expect(formatExactCost(1.1)).toBe("$1.10");
    expect(formatExactCost(0.123456789)).toBe("$0.123457");
    expect(formatExactCost(1234.5678)).toBe("$1,234.57");
    expect(formatExactCost("0.0005")).toBe("$0.0005");
    expect(formatExactCost(0)).toBe("$0.00");
  });

  it("returns the fallback for a missing value", () => {
    expect(formatExactCost(undefined)).toBe("-");
    expect(formatExactCost(null, "")).toBe("");
    expect(formatExactCost("abc")).toBe("-");
  });
});
