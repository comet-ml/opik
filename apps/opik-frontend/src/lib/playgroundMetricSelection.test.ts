import { describe, expect, it } from "vitest";

import {
  getDefaultMetricRuleSelection,
  getMetricRuleSelectionOrDefault,
  toggleMetricRuleSelection,
} from "./playgroundMetricSelection";

describe("playgroundMetricSelection", () => {
  it("uses null for select-all defaults and [] for select-none defaults", () => {
    expect(getDefaultMetricRuleSelection(true)).toBeNull();
    expect(getDefaultMetricRuleSelection(false)).toEqual([]);
  });

  it("uses the default only when no saved selection exists", () => {
    expect(getMetricRuleSelectionOrDefault(undefined, [])).toEqual([]);
    expect(getMetricRuleSelectionOrDefault(null, [])).toBeNull();
    expect(getMetricRuleSelectionOrDefault([], null)).toEqual([]);
    expect(getMetricRuleSelectionOrDefault(["rule-1"], null)).toEqual([
      "rule-1",
    ]);
  });

  it("can keep manual all-selection explicit when null is reserved as a default sentinel", () => {
    expect(
      toggleMetricRuleSelection(["rule-1", "rule-2"], ["rule-1"], "rule-2", {
        useExplicitRuleIdsForAll: true,
      }),
    ).toEqual(["rule-1", "rule-2"]);
  });

  it("uses null for all-selection when explicit rule IDs are not required", () => {
    expect(
      toggleMetricRuleSelection(["rule-1", "rule-2"], ["rule-1"], "rule-2"),
    ).toBeNull();
  });
});
