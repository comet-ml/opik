import { describe, it, expect } from "vitest";

import { toggleAllMetrics, toggleMetricSelection } from "./metricSelection";

const ALL = ["a", "b", "c"];

describe("toggleMetricSelection", () => {
  it("selects an id from none", () => {
    expect(toggleMetricSelection([], "a")).toEqual(["a"]);
  });

  it("treats a legacy null as none", () => {
    expect(toggleMetricSelection(null, "a")).toEqual(["a"]);
  });

  it("appends an id to an existing selection", () => {
    expect(toggleMetricSelection(["a"], "b")).toEqual(["a", "b"]);
  });

  it("deselects an already selected id", () => {
    expect(toggleMetricSelection(["a", "b", "c"], "b")).toEqual(["a", "c"]);
  });

  it("deselecting the last remaining id yields none", () => {
    expect(toggleMetricSelection(["b"], "b")).toEqual([]);
  });

  it("keeps the full set as an explicit list", () => {
    expect(toggleMetricSelection(["a", "b"], "c")).toEqual(["a", "b", "c"]);
  });
});

describe("toggleAllMetrics", () => {
  it("clears to none when everything is currently selected", () => {
    expect(toggleAllMetrics(true, ALL)).toEqual([]);
  });

  it("selects every rule when not everything is selected", () => {
    expect(toggleAllMetrics(false, ALL)).toEqual(ALL);
  });
});
