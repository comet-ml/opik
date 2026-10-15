import { describe, it, expect } from "vitest";

import { EVAL_TRIGGER_SCOPE, EvaluatorsRule } from "@/types/automations";

import {
  isAlwaysRunRule,
  toggleAllMetrics,
  toggleMetricSelection,
} from "./metricSelection";

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

describe("isAlwaysRunRule", () => {
  const rule = (overrides: Partial<EvaluatorsRule>) =>
    ({ id: "r", enabled: true, ...overrides }) as EvaluatorsRule;

  it("is true for an enabled rule scoped to experiments", () => {
    expect(
      isAlwaysRunRule(rule({ trigger_scope: EVAL_TRIGGER_SCOPE.experiment })),
    ).toBe(true);
  });

  it("is true for an enabled rule scoped to both", () => {
    expect(
      isAlwaysRunRule(rule({ trigger_scope: EVAL_TRIGGER_SCOPE.both })),
    ).toBe(true);
  });

  it("is false for a production-scoped rule", () => {
    expect(
      isAlwaysRunRule(rule({ trigger_scope: EVAL_TRIGGER_SCOPE.production })),
    ).toBe(false);
  });

  it("is false for a disabled rule whatever its scope", () => {
    expect(
      isAlwaysRunRule(
        rule({ trigger_scope: EVAL_TRIGGER_SCOPE.experiment, enabled: false }),
      ),
    ).toBe(false);
  });

  it("treats a missing enabled flag as enabled", () => {
    expect(
      isAlwaysRunRule(
        rule({ trigger_scope: EVAL_TRIGGER_SCOPE.both, enabled: undefined }),
      ),
    ).toBe(true);
  });
});
