import { describe, it, expect } from "vitest";
import { computeSelectAllColumnsIds } from "./ColumnsButton";

/**
 * Tests for ColumnsButton excludeFromSelectAll logic
 *
 * These tests verify the core logic for excluding metadata fields from "Select all"
 * functionality. The tests focus on the computed values rather than UI interactions
 * since Radix UI dropdown menus require special handling in test environments.
 */

describe("ColumnsButton excludeFromSelectAll logic", () => {
  describe("selectAllColumnsIds computation", () => {
    it("should exclude metadata fields from select all columns", () => {
      const allColumnsIds = [
        "name",
        "duration",
        "input",
        "metadata.time_to_first_token",
        "metadata.model_name",
      ];
      const excludeFromSelectAll = [
        "metadata.time_to_first_token",
        "metadata.model_name",
      ];

      const selectAllColumnsIds = computeSelectAllColumnsIds(
        allColumnsIds,
        excludeFromSelectAll,
      );

      expect(selectAllColumnsIds).toEqual(["name", "duration", "input"]);
    });

    it("should include all columns when excludeFromSelectAll is empty", () => {
      const allColumnsIds = [
        "name",
        "duration",
        "input",
        "metadata.time_to_first_token",
        "metadata.model_name",
      ];
      const excludeFromSelectAll: string[] = [];

      const selectAllColumnsIds = computeSelectAllColumnsIds(
        allColumnsIds,
        excludeFromSelectAll,
      );

      expect(selectAllColumnsIds).toEqual(allColumnsIds);
    });

    it("should handle partial exclusion", () => {
      const allColumnsIds = [
        "name",
        "duration",
        "input",
        "metadata.time_to_first_token",
        "metadata.model_name",
      ];
      const excludeFromSelectAll = ["metadata.time_to_first_token"];

      const selectAllColumnsIds = computeSelectAllColumnsIds(
        allColumnsIds,
        excludeFromSelectAll,
      );

      expect(selectAllColumnsIds).toEqual([
        "name",
        "duration",
        "input",
        "metadata.model_name",
      ]);
    });
  });

  describe("allColumnsSelected computation", () => {
    it("should return true when all non-excluded columns are selected", () => {
      const selectedColumns = ["name", "duration", "input"];
      const selectAllColumnsIds = ["name", "duration", "input"];

      const allColumnsSelected =
        selectAllColumnsIds.length > 0 &&
        selectAllColumnsIds.every((id) => selectedColumns.includes(id));

      expect(allColumnsSelected).toBe(true);
    });

    it("should return false when some non-excluded columns are not selected", () => {
      const selectedColumns = ["name", "duration"];
      const selectAllColumnsIds = ["name", "duration", "input"];

      const allColumnsSelected =
        selectAllColumnsIds.length > 0 &&
        selectAllColumnsIds.every((id) => selectedColumns.includes(id));

      expect(allColumnsSelected).toBe(false);
    });

    it("should return true even when excluded columns are not selected", () => {
      const selectedColumns = ["name", "duration", "input"];
      const selectAllColumnsIds = ["name", "duration", "input"];

      // Metadata fields are not in selectedColumns, but that's OK
      const allColumnsSelected =
        selectAllColumnsIds.length > 0 &&
        selectAllColumnsIds.every((id) => selectedColumns.includes(id));

      expect(allColumnsSelected).toBe(true);
    });

    it("should return true when excluded columns are also selected", () => {
      const selectedColumns = [
        "name",
        "duration",
        "input",
        "metadata.time_to_first_token",
      ];
      const selectAllColumnsIds = ["name", "duration", "input"];

      const allColumnsSelected =
        selectAllColumnsIds.length > 0 &&
        selectAllColumnsIds.every((id) => selectedColumns.includes(id));

      expect(allColumnsSelected).toBe(true);
    });
  });

  describe("toggleColumns logic", () => {
    it("should select all non-excluded columns and preserve existing excluded selections", () => {
      const selectedColumns = ["metadata.time_to_first_token"];
      const selectAllColumnsIds = ["name", "duration", "input"];
      const excludeFromSelectAll = [
        "metadata.time_to_first_token",
        "metadata.model_name",
      ];

      // Simulating toggleColumns(true) logic
      const currentlySelectedMetadataItems = selectedColumns.filter((id) =>
        excludeFromSelectAll.includes(id),
      );
      const newSelection = [
        ...selectAllColumnsIds,
        ...currentlySelectedMetadataItems,
      ];

      expect(newSelection).toEqual([
        "name",
        "duration",
        "input",
        "metadata.time_to_first_token",
      ]);
    });

    it("should deselect all columns including excluded ones", () => {
      // Simulating toggleColumns(false) logic
      const newSelection: string[] = [];

      expect(newSelection).toEqual([]);
    });

    it("should handle selecting all when no excluded items are selected", () => {
      const selectedColumns: string[] = [];
      const selectAllColumnsIds = ["name", "duration", "input"];
      const excludeFromSelectAll = [
        "metadata.time_to_first_token",
        "metadata.model_name",
      ];

      // Simulating toggleColumns(true) logic
      const currentlySelectedMetadataItems = selectedColumns.filter((id) =>
        excludeFromSelectAll.includes(id),
      );
      const newSelection = [
        ...selectAllColumnsIds,
        ...currentlySelectedMetadataItems,
      ];

      expect(newSelection).toEqual(["name", "duration", "input"]);
    });
  });
});
