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
import { ExperimentItemStatus } from "@/types/test-suites";
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

  describe("evaluating state", () => {
    it("returns evaluating when items exist with evaluators but no status", () => {
      const row = makeRow({
        experiment_items: [makeItem({ id: "i1" })],
        evaluators: [{ name: "judge", type: "LLM_AS_JUDGE", config: {} }],
      });
      const result = getStatusFromExperimentItems(row);
      expect(result.evaluating).toBe(true);
      expect(result.status).toBeUndefined();
    });

    it("returns not evaluating when status is resolved", () => {
      const row = makeRow({
        experiment_items: [
          makeItem({ id: "i1", status: ExperimentItemStatus.PASSED }),
        ],
        evaluators: [{ name: "judge", type: "LLM_AS_JUDGE", config: {} }],
      });
      const result = getStatusFromExperimentItems(row);
      expect(result.evaluating).toBe(false);
      expect(result.status).toBe(ExperimentItemStatus.PASSED);
    });
  });

  describe("execution_policy", () => {
    it("uses dataset-level execution_policy when items have none", () => {
      const row = makeRow({
        execution_policy: { runs_per_item: 5, pass_threshold: 3 },
        experiment_items: [
          makeItem({ id: "i1", status: ExperimentItemStatus.PASSED }),
        ],
      });
      const result = getStatusFromExperimentItems(row);
      expect(result.passThreshold).toBe(3);
      expect(result.runsPerItem).toBe(5);
    });

    it("item-level execution_policy overrides dataset-level", () => {
      const row = makeRow({
        execution_policy: { runs_per_item: 5, pass_threshold: 3 },
        experiment_items: [
          makeItem({
            id: "i1",
            status: ExperimentItemStatus.PASSED,
            execution_policy: { runs_per_item: 2, pass_threshold: 1 },
          }),
        ],
      });
      const result = getStatusFromExperimentItems(row);
      expect(result.passThreshold).toBe(1);
      expect(result.runsPerItem).toBe(2);
    });

    it("returns undefined when no execution_policy exists", () => {
      const row = makeRow({
        experiment_items: [
          makeItem({ id: "i1", status: ExperimentItemStatus.PASSED }),
        ],
      });
      const result = getStatusFromExperimentItems(row);
      expect(result.passThreshold).toBeUndefined();
      expect(result.runsPerItem).toBeUndefined();
    });
  });

  describe("assertionsByRun", () => {
    it("maps assertion_results from each item", () => {
      const row = makeRow({
        experiment_items: [
          makeItem({
            id: "i1",
            status: ExperimentItemStatus.PASSED,
            assertion_results: [{ value: "a", passed: true }],
          }),
          makeItem({
            id: "i2",
            status: ExperimentItemStatus.FAILED,
            assertion_results: [{ value: "a", passed: false }],
          }),
        ],
      });
      const result = getStatusFromExperimentItems(row);
      expect(result.assertionsByRun).toHaveLength(2);
      expect(result.assertionsByRun[0][0].passed).toBe(true);
      expect(result.assertionsByRun[1][0].passed).toBe(false);
    });

    it("defaults to empty array for items without assertion_results", () => {
      const row = makeRow({
        experiment_items: [
          makeItem({ id: "i1", status: ExperimentItemStatus.PASSED }),
        ],
      });
      const result = getStatusFromExperimentItems(row);
      expect(result.assertionsByRun).toEqual([[]]);
    });
  });
});

describe("getStatusInfoForExperiment", () => {
  describe("no item", () => {
    it("returns SKIPPED with 'no experiment item' reason when item is undefined", () => {
      const result = getStatusInfoForExperiment(makeRow(), "exp-1", undefined);
      expect(result.status).toBe(ExperimentItemStatus.SKIPPED);
      expect(result.skippedReason).toBe("No experiment item defined");
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

  describe("with run summary", () => {
    it("uses summary status over item status", () => {
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
      expect(result.status).toBe(ExperimentItemStatus.PASSED);
    });

    it("falls back to item status when no summary for that experimentId", () => {
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
      expect(result.status).toBe(ExperimentItemStatus.PASSED);
      expect(result.runsPerItem).toBe(3);
    });
  });

  describe("evaluating state", () => {
    it("returns evaluating when item exists with evaluators but no status", () => {
      const row = makeRow({
        evaluators: [{ name: "judge", type: "LLM_AS_JUDGE", config: {} }],
      });
      const item = makeItem({ id: "i1" });
      const result = getStatusInfoForExperiment(row, "exp-1", item);
      expect(result.evaluating).toBe(true);
      expect(result.status).toBeUndefined();
    });

    it("returns not evaluating when summary provides status", () => {
      const row = makeRow({
        evaluators: [{ name: "judge", type: "LLM_AS_JUDGE", config: {} }],
        run_summaries_by_experiment: {
          "exp-1": {
            passed_runs: 1,
            total_runs: 1,
            status: ExperimentItemStatus.PASSED,
          },
        },
      });
      const item = makeItem({ id: "i1" });
      const result = getStatusInfoForExperiment(row, "exp-1", item);
      expect(result.evaluating).toBe(false);
      expect(result.status).toBe(ExperimentItemStatus.PASSED);
    });
  });

  describe("execution_policy", () => {
    it("uses dataset-level execution_policy when item has none", () => {
      const row = makeRow({
        execution_policy: { runs_per_item: 4, pass_threshold: 3 },
      });
      const item = makeItem({ id: "i1", status: ExperimentItemStatus.PASSED });
      const result = getStatusInfoForExperiment(row, "exp-1", item);
      expect(result.passThreshold).toBe(3);
      expect(result.runsPerItem).toBe(4);
    });

    it("item-level execution_policy overrides dataset-level", () => {
      const row = makeRow({
        execution_policy: { runs_per_item: 4, pass_threshold: 3 },
      });
      const item = makeItem({
        id: "i1",
        status: ExperimentItemStatus.PASSED,
        execution_policy: { runs_per_item: 2, pass_threshold: 1 },
      });
      const result = getStatusInfoForExperiment(row, "exp-1", item);
      expect(result.passThreshold).toBe(1);
      expect(result.runsPerItem).toBe(2);
    });

    it("returns undefined when no execution_policy exists", () => {
      const item = makeItem({ id: "i1", status: ExperimentItemStatus.PASSED });
      const result = getStatusInfoForExperiment(makeRow(), "exp-1", item);
      expect(result.passThreshold).toBeUndefined();
      expect(result.runsPerItem).toBeUndefined();
    });
  });

  describe("aggregated item", () => {
    it("expands trialItems for assertionsByRun", () => {
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
      expect(result.assertionsByRun).toHaveLength(2);
      expect(result.assertionsByRun[0][0].passed).toBe(true);
      expect(result.assertionsByRun[1][0].passed).toBe(false);
    });

    it("uses item-level execution_policy from first trialItem", () => {
      const row = makeRow({
        execution_policy: { runs_per_item: 10, pass_threshold: 5 },
      });
      const aggregated = {
        ...makeItem({ id: "agg", status: ExperimentItemStatus.PASSED }),
        trialCount: 2,
        trialItems: [
          makeItem({
            id: "t1",
            status: ExperimentItemStatus.PASSED,
            execution_policy: { runs_per_item: 3, pass_threshold: 2 },
          }),
          makeItem({ id: "t2", status: ExperimentItemStatus.PASSED }),
        ],
      } as AggregatedExperimentItem;
      const result = getStatusInfoForExperiment(row, "exp-1", aggregated);
      expect(result.passThreshold).toBe(2);
      expect(result.runsPerItem).toBe(3);
    });
  });
});
