import { describe, expect, it } from "vitest";

import {
  getDatasetMetricRuleSelectionUpdate,
  getDefaultMetricRuleSelection,
  getMetricRuleSelectionOrDefault,
  getResolvedMetricRuleSelectionDefault,
  toggleMetricRuleSelection,
} from "./playgroundMetricSelection";

describe("playgroundMetricSelection", () => {
  describe("getDefaultMetricRuleSelection", () => {
    it("should use null for select-all defaults and [] for select-none defaults", () => {
      expect(getDefaultMetricRuleSelection(true)).toBeNull();
      expect(getDefaultMetricRuleSelection(false)).toEqual([]);
    });
  });

  describe("getMetricRuleSelectionOrDefault", () => {
    it("should use the default only when no saved or unsaved selection exists", () => {
      expect(getMetricRuleSelectionOrDefault(undefined, [])).toEqual([]);
      expect(getMetricRuleSelectionOrDefault(null, [])).toBeNull();
      expect(getMetricRuleSelectionOrDefault([], null)).toEqual([]);
      expect(getMetricRuleSelectionOrDefault(["rule-1"], null)).toEqual([
        "rule-1",
      ]);
    });
  });

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
      expect(
        toggleMetricRuleSelection(["rule-1", "rule-2"], ["rule-1"], "rule-2", {
          useExplicitRuleIdsForAll: true,
        }),
      ).toEqual(["rule-1", "rule-2"]);
    });

    it("should use null for all-selection when explicit rule IDs are not required", () => {
      expect(
        toggleMetricRuleSelection(["rule-1", "rule-2"], ["rule-1"], "rule-2"),
      ).toBeNull();
    });
  });
});
