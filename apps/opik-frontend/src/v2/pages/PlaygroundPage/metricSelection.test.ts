import { describe, it, expect } from "vitest";

import { toggleAllMetrics, toggleMetricSelection } from "./metricSelection";

const ALL = ["a", "b", "c"];

describe("toggleMetricSelection", () => {
  it("from all (null): deselecting one yields everything-but-that-one", () => {
    expect(toggleMetricSelection(null, "b", ALL)).toEqual(["a", "c"]);
  });

  it("from all (explicit full array): deselecting one yields the rest", () => {
    expect(toggleMetricSelection(["a", "b", "c"], "a", ALL)).toEqual([
      "b",
      "c",
    ]);
  });

  it("deselecting the last remaining id collapses to [] (none)", () => {
    expect(toggleMetricSelection(["b"], "b", ALL)).toEqual([]);
  });

  it("from none ([]): selecting one yields just that id", () => {
    expect(toggleMetricSelection([], "a", ALL)).toEqual(["a"]);
  });

  it("selecting an id that completes the full set collapses to null (all)", () => {
    expect(toggleMetricSelection(["a", "b"], "c", ALL)).toBeNull();
  });

  it("selecting an additional id (not completing the set) keeps an array", () => {
    expect(toggleMetricSelection(["a"], "b", ALL)).toEqual(["a", "b"]);
  });

  it("single-rule set: selecting the only rule from none collapses to null (all)", () => {
    expect(toggleMetricSelection([], "a", ["a"])).toBeNull();
  });

  it("from all (null) with a single rule: deselecting it yields []", () => {
    expect(toggleMetricSelection(null, "a", ["a"])).toEqual([]);
  });
});

describe("toggleAllMetrics", () => {
  it("clears to [] when everything is currently selected", () => {
    expect(toggleAllMetrics(true)).toEqual([]);
  });

  it("selects all (null) when not everything is selected", () => {
    expect(toggleAllMetrics(false)).toBeNull();
  });
});
