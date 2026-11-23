import { describe, expect, it } from "vitest";
import { formatPrice } from "./format";

describe("formatPrice", () => {
  it("groups thousands and keeps two decimals from $1 up", () => {
    expect(formatPrice("3200")).toBe("$3,200.00");
    expect(formatPrice("1250000.5")).toBe("$1,250,000.50");
    expect(formatPrice("1.25")).toBe("$1.25");
  });

  it("keeps three decimals below $1 and two significant digits below $0.001", () => {
    expect(formatPrice("0.15")).toBe("$0.150");
    expect(formatPrice("0")).toBe("$0.000");
    expect(formatPrice("0.00012")).toBe("$0.00012");
  });

  it("returns nothing for a missing price", () => {
    expect(formatPrice(undefined)).toBeUndefined();
    expect(formatPrice("")).toBeUndefined();
  });
});
