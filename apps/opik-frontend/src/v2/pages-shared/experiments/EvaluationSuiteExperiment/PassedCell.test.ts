import { describe, it, expect } from "vitest";
import {
  getStatusFromExperimentItems,
  getStatusInfoForExperiment,
} from "./PassedCell";
import {
  ExperimentItem,
  ExperimentsCompare,
  DATASET_ITEM_SOURCE,
} from "@/types/datasets";
import { ExperimentItemStatus } from "@/types/evaluation-suites";
import { AggregatedExperimentItem } from "@/lib/trials";

const makeItem = (
  overrides: Partial<ExperimentItem> & { id: string },
): ExperimentItem => ({
  experiment_id: "exp-1",
  dataset_item_id: "di-1",
  input: {},
  output: {},
  created_at: "2025-01-01T00:00:00Z",
  last_updated_at: "2025-01-01T00:00:00Z",
  ...overrides,
});

const makeRow = (
  overrides: Partial<ExperimentsCompare> = {},
): ExperimentsCompare => ({
  id: "row-1",
  data: {},
  source: DATASET_ITEM_SOURCE.sdk,
  created_at: "2025-01-01T00:00:00Z",
  last_updated_at: "2025-01-01T00:00:00Z",
  experiment_items: [],
  ...overrides,
});

describe("getStatusFromExperimentItems", () => {
  describe("no items", () => {
    it("returns SKIPPED with 'no experiment item' reason when experiment_items is empty", () => {
      const result = getStatusFromExperimentItems(makeRow());
      expect(result.status).toBe(ExperimentItemStatus.SKIPPED);
      expect(result.skippedReason).toBe("No experiment item defined");
      expect(result.totalCount).toBe(0);
    });
  });

  describe("items without assertions", () => {
    it("returns SKIPPED with 'no assertions' reason when items have no status", () => {
      const row = makeRow({
        experiment_items: [makeItem({ id: "i1" })],
      });
      const result = getStatusFromExperimentItems(row);
      expect(result.status).toBe(ExperimentItemStatus.SKIPPED);
      expect(result.skippedReason).toBe("No assertions defined");
    });
  });

  describe("totalCount", () => {
    it("uses items.length when no execution_policy is set", () => {
      const row = makeRow({
        experiment_items: [
          makeItem({ id: "i1", status: ExperimentItemStatus.PASSED }),
          makeItem({ id: "i2", status: ExperimentItemStatus.PASSED }),
          makeItem({ id: "i3", status: ExperimentItemStatus.PASSED }),
        ],
      });
      const result = getStatusFromExperimentItems(row);
      expect(result.totalCount).toBe(3);
    });

    it("uses runs_per_item from execution_policy when set", () => {
      const row = makeRow({
        execution_policy: { runs_per_item: 5, pass_threshold: 3 },
        experiment_items: [
          makeItem({ id: "i1", status: ExperimentItemStatus.PASSED }),
          makeItem({ id: "i2", status: ExperimentItemStatus.PASSED }),
        ],
      });
      const result = getStatusFromExperimentItems(row);
      // 5 from policy, not 2 from items array
      expect(result.totalCount).toBe(5);
    });
  });

  describe("status from run_summaries_by_experiment", () => {
    it("returns PASSED when all summaries are PASSED", () => {
      const row = makeRow({
        experiment_items: [makeItem({ id: "i1" })],
        run_summaries_by_experiment: {
          "exp-1": {
            passed_runs: 2,
            total_runs: 2,
            status: ExperimentItemStatus.PASSED,
          },
          "exp-2": {
            passed_runs: 3,
            total_runs: 3,
            status: ExperimentItemStatus.PASSED,
          },
        },
      });
      expect(getStatusFromExperimentItems(row).status).toBe(
        ExperimentItemStatus.PASSED,
      );
    });

    it("returns FAILED when any summary is not PASSED", () => {
      const row = makeRow({
        experiment_items: [makeItem({ id: "i1" })],
        run_summaries_by_experiment: {
          "exp-1": {
            passed_runs: 2,
            total_runs: 3,
            status: ExperimentItemStatus.FAILED,
          },
        },
      });
      expect(getStatusFromExperimentItems(row).status).toBe(
        ExperimentItemStatus.FAILED,
      );
    });

    it("falls back to first item status when no summaries", () => {
      const row = makeRow({
        experiment_items: [
          makeItem({ id: "i1", status: ExperimentItemStatus.SKIPPED }),
        ],
      });
      expect(getStatusFromExperimentItems(row).status).toBe(
        ExperimentItemStatus.SKIPPED,
      );
    });
  });
});

describe("getStatusInfoForExperiment", () => {
  describe("no item", () => {
    it("returns SKIPPED with 'no experiment item' reason when item is undefined", () => {
      const result = getStatusInfoForExperiment(makeRow(), "exp-1", undefined);
      expect(result.status).toBe(ExperimentItemStatus.SKIPPED);
      expect(result.skippedReason).toBe("No experiment item defined");
      expect(result.passedCount).toBe(0);
      expect(result.totalCount).toBe(0);
    });
  });

  describe("item without assertions", () => {
    it("returns SKIPPED with 'no assertions' reason when item has no status", () => {
      const item = makeItem({ id: "i1" });
      const result = getStatusInfoForExperiment(makeRow(), "exp-1", item);
      expect(result.status).toBe(ExperimentItemStatus.SKIPPED);
      expect(result.skippedReason).toBe("No assertions defined");
    });
  });

  describe("totalCount without summary", () => {
    it("returns 0 when no summary and no execution_policy", () => {
      const row = makeRow();
      const item = makeItem({ id: "i1", status: ExperimentItemStatus.PASSED });
      const result = getStatusInfoForExperiment(row, "exp-1", item);
      expect(result.totalCount).toBe(0);
    });

    it("uses runs_per_item when no summary but execution_policy is set", () => {
      const row = makeRow({
        execution_policy: { runs_per_item: 4, pass_threshold: 3 },
      });
      const item = makeItem({ id: "i1", status: ExperimentItemStatus.PASSED });
      const result = getStatusInfoForExperiment(row, "exp-1", item);
      expect(result.totalCount).toBe(4);
    });
  });

  describe("with run summary", () => {
    it("uses summary.passed_runs for passedCount", () => {
      const row = makeRow({
        run_summaries_by_experiment: {
          "exp-1": {
            passed_runs: 3,
            total_runs: 5,
            status: ExperimentItemStatus.PASSED,
          },
        },
      });
      const item = makeItem({ id: "i1", status: ExperimentItemStatus.FAILED });
      const result = getStatusInfoForExperiment(row, "exp-1", item);
      expect(result.passedCount).toBe(3);
    });

    it("uses summary.total_runs for totalCount", () => {
      const row = makeRow({
        run_summaries_by_experiment: {
          "exp-1": {
            passed_runs: 3,
            total_runs: 5,
            status: ExperimentItemStatus.PASSED,
          },
        },
      });
      const item = makeItem({ id: "i1", status: ExperimentItemStatus.PASSED });
      const result = getStatusInfoForExperiment(row, "exp-1", item);
      expect(result.totalCount).toBe(5);
    });

    it("summary.total_runs takes precedence over execution_policy.runs_per_item", () => {
      const row = makeRow({
        execution_policy: { runs_per_item: 10, pass_threshold: 5 },
        run_summaries_by_experiment: {
          "exp-1": {
            passed_runs: 3,
            total_runs: 5,
            status: ExperimentItemStatus.PASSED,
          },
        },
      });
      const item = makeItem({ id: "i1", status: ExperimentItemStatus.PASSED });
      const result = getStatusInfoForExperiment(row, "exp-1", item);
      expect(result.totalCount).toBe(5);
    });

    it("uses local passedCount when no summary for that experimentId", () => {
      const row = makeRow({
        run_summaries_by_experiment: {
          "exp-other": {
            passed_runs: 99,
            total_runs: 99,
            status: ExperimentItemStatus.PASSED,
          },
        },
        execution_policy: { runs_per_item: 3, pass_threshold: 2 },
      });
      const item = makeItem({ id: "i1", status: ExperimentItemStatus.PASSED });
      const result = getStatusInfoForExperiment(row, "exp-1", item);
      // No summary for exp-1, so passedCount comes from local items (1 PASSED item)
      expect(result.passedCount).toBe(1);
      // totalCount from execution_policy since no summary for exp-1
      expect(result.totalCount).toBe(3);
    });
  });

  describe("aggregated item", () => {
    it("expands trialItems for passedCount and assertionsByRun", () => {
      const row = makeRow();
      const aggregated = {
        ...makeItem({ id: "agg", status: ExperimentItemStatus.FAILED }),
        trialCount: 2,
        trialItems: [
          makeItem({
            id: "t1",
            status: ExperimentItemStatus.PASSED,
            assertion_results: [{ value: "check", passed: true }],
          }),
          makeItem({
            id: "t2",
            status: ExperimentItemStatus.FAILED,
            assertion_results: [{ value: "check", passed: false }],
          }),
        ],
      } as AggregatedExperimentItem;
      const result = getStatusInfoForExperiment(row, "exp-1", aggregated);
      expect(result.passedCount).toBe(1);
      expect(result.assertionsByRun).toHaveLength(2);
      expect(result.assertionsByRun[0][0].passed).toBe(true);
      expect(result.assertionsByRun[1][0].passed).toBe(false);
    });
  });
});
