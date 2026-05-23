import { describe, expect, it } from "vitest";

import {
  getDatasetMetricRuleSelectionUpdate,
  getResolvedMetricRuleSelectionDefault,
  isMetricRuleSelectionAll,
  toggleMetricRuleSelection,
} from "./playgroundMetricSelection";

describe("playgroundMetricSelection", () => {
  describe("getResolvedMetricRuleSelectionDefault", () => {
    it("should resolve select-none defaults before rules are loaded", () => {
      expect(getResolvedMetricRuleSelectionDefault([], false)).toEqual([]);
    });

    it("should wait for rules before resolving select-all defaults", () => {
      expect(getResolvedMetricRuleSelectionDefault([], true)).toBeUndefined();
    });

    it("should resolve select-all defaults to rule IDs when rules are loaded", () => {
      expect(
        getResolvedMetricRuleSelectionDefault(["rule-1", "rule-2"], true),
      ).toEqual(["rule-1", "rule-2"]);
    });
  });

  describe("getDatasetMetricRuleSelectionUpdate", () => {
    it("should keep null selection when no dataset is selected", () => {
      expect(
        getDatasetMetricRuleSelectionUpdate({
          datasetId: null,
          trackedDatasetId: "dataset-1::version-1",
          selectedRuleIds: null,
          ruleIds: ["rule-1"],
          selectAllMetricsByDefault: true,
        }),
      ).toEqual({ trackedDatasetId: null });
    });

    it("should preserve explicit selections while dataset selection hydrates", () => {
      expect(
        getDatasetMetricRuleSelectionUpdate({
          datasetId: null,
          trackedDatasetId: undefined,
          selectedRuleIds: ["rule-1"],
          ruleIds: ["rule-1"],
          selectAllMetricsByDefault: true,
        }),
      ).toEqual({ trackedDatasetId: undefined });
    });

    it("should clear explicit selections when no dataset is selected", () => {
      expect(
        getDatasetMetricRuleSelectionUpdate({
          datasetId: null,
          trackedDatasetId: "dataset-1::version-1",
          selectedRuleIds: ["rule-1"],
          ruleIds: ["rule-1"],
          selectAllMetricsByDefault: true,
        }),
      ).toEqual({ trackedDatasetId: null, nextSelectedRuleIds: null });
    });

    it("should preserve explicit selections after a dataset is tracked", () => {
      expect(
        getDatasetMetricRuleSelectionUpdate({
          datasetId: "dataset-1::version-1",
          trackedDatasetId: "dataset-1::version-1",
          selectedRuleIds: ["rule-1"],
          ruleIds: ["rule-1", "rule-2"],
          selectAllMetricsByDefault: true,
        }),
      ).toEqual({ trackedDatasetId: "dataset-1::version-1" });
    });

    it("should preserve valid explicit selections on first hydration", () => {
      expect(
        getDatasetMetricRuleSelectionUpdate({
          datasetId: "dataset-1::version-1",
          trackedDatasetId: undefined,
          selectedRuleIds: ["rule-1"],
          ruleIds: ["rule-1", "rule-2"],
          selectAllMetricsByDefault: true,
        }),
      ).toEqual({
        trackedDatasetId: "dataset-1::version-1",
      });
    });

    it("should preserve explicit selections while rules load on first hydration", () => {
      expect(
        getDatasetMetricRuleSelectionUpdate({
          datasetId: "dataset-1::version-1",
          trackedDatasetId: undefined,
          selectedRuleIds: ["rule-1"],
          ruleIds: [],
          selectAllMetricsByDefault: true,
        }),
      ).toEqual({
        trackedDatasetId: undefined,
      });
    });

    it("should re-resolve stale explicit selections on first hydration", () => {
      expect(
        getDatasetMetricRuleSelectionUpdate({
          datasetId: "dataset-1::version-1",
          trackedDatasetId: undefined,
          selectedRuleIds: ["stale-rule"],
          ruleIds: ["rule-1", "rule-2"],
          selectAllMetricsByDefault: true,
        }),
      ).toEqual({
        trackedDatasetId: "dataset-1::version-1",
        nextSelectedRuleIds: ["rule-1", "rule-2"],
      });
    });

    it("should preserve explicit empty selections while rules load on first hydration", () => {
      expect(
        getDatasetMetricRuleSelectionUpdate({
          datasetId: "dataset-1::version-1",
          trackedDatasetId: undefined,
          selectedRuleIds: [],
          ruleIds: [],
          selectAllMetricsByDefault: true,
        }),
      ).toEqual({
        trackedDatasetId: undefined,
      });
    });

    it("should resolve select-none defaults before rules are loaded", () => {
      expect(
        getDatasetMetricRuleSelectionUpdate({
          datasetId: "dataset-1::version-1",
          trackedDatasetId: undefined,
          selectedRuleIds: null,
          ruleIds: [],
          selectAllMetricsByDefault: false,
        }),
      ).toEqual({
        trackedDatasetId: "dataset-1::version-1",
        nextSelectedRuleIds: [],
      });
    });

    it("should clear stale explicit selections when a dataset changes before rules load", () => {
      expect(
        getDatasetMetricRuleSelectionUpdate({
          datasetId: "dataset-2::version-1",
          trackedDatasetId: "dataset-1::version-1",
          selectedRuleIds: ["rule-1"],
          ruleIds: [],
          selectAllMetricsByDefault: true,
        }),
      ).toEqual({
        trackedDatasetId: "dataset-1::version-1",
        nextSelectedRuleIds: null,
      });
    });

    it("should resolve default rule IDs when a dataset changes after rules load", () => {
      expect(
        getDatasetMetricRuleSelectionUpdate({
          datasetId: "dataset-2::version-1",
          trackedDatasetId: "dataset-1::version-1",
          selectedRuleIds: null,
          ruleIds: ["rule-1", "rule-2"],
          selectAllMetricsByDefault: true,
        }),
      ).toEqual({
        trackedDatasetId: "dataset-2::version-1",
        nextSelectedRuleIds: ["rule-1", "rule-2"],
      });
    });
  });

  describe("toggleMetricRuleSelection", () => {
    it("should keep manual all-selection explicit when null is reserved as a default sentinel", () => {
      const ruleIds = ["rule-1", "rule-2"];
      const selection = toggleMetricRuleSelection(
        ruleIds,
        ["rule-1"],
        "rule-2",
        {
          useExplicitRuleIdsForAll: true,
        },
      );

      expect(selection).toEqual(ruleIds);
      expect(selection).not.toBe(ruleIds);
    });

    it("should use null for all-selection when explicit rule IDs are not required", () => {
      expect(
        toggleMetricRuleSelection(["rule-1", "rule-2"], ["rule-1"], "rule-2", {
          useExplicitRuleIdsForAll: false,
        }),
      ).toBeNull();
    });
  });

  describe("isMetricRuleSelectionAll", () => {
    it("should return true when all rule IDs are selected explicitly", () => {
      expect(
        isMetricRuleSelectionAll(["rule-1", "rule-2"], ["rule-1", "rule-2"]),
      ).toBe(true);
    });

    it("should return true when all rule IDs are selected by sentinel", () => {
      expect(isMetricRuleSelectionAll(["rule-1"], null)).toBe(true);
    });

    it("should return false when only some rule IDs are selected", () => {
      expect(isMetricRuleSelectionAll(["rule-1", "rule-2"], ["rule-1"])).toBe(
        false,
      );
    });

    it("should return false when no rule IDs are selected", () => {
      expect(isMetricRuleSelectionAll(["rule-1"], [])).toBe(false);
    });
  });
});
