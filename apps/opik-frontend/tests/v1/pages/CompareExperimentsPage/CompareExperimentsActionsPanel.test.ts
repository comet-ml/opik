import { describe, expect, it } from "vitest";

import {
  processPassedExportColumn,
  resolvePassedStatus,
} from "@/v1/pages/CompareExperimentsPage/compareExperimentsExportUtils";
import {
  DATASET_ITEM_SOURCE,
  type Evaluator,
  type ExperimentItem,
  type ExperimentsCompare,
} from "@/types/datasets";
import { RunStatus } from "@/types/test-suites";

const createExperimentItem = (
  overrides: Partial<ExperimentItem> = {},
): ExperimentItem => ({
  id: "item-1",
  experiment_id: "experiment-1",
  dataset_item_id: "dataset-item-1",
  input: {},
  output: {},
  created_at: "2026-05-22T00:00:00.000Z",
  last_updated_at: "2026-05-22T00:00:00.000Z",
  ...overrides,
});

const createCompareRow = (
  overrides: Partial<ExperimentsCompare> = {},
): ExperimentsCompare => ({
  id: "dataset-item-1",
  data: {},
  source: DATASET_ITEM_SOURCE.manual,
  created_at: "2026-05-22T00:00:00.000Z",
  last_updated_at: "2026-05-22T00:00:00.000Z",
  experiment_items: [],
  ...overrides,
});

const evaluators: Evaluator[] = [
  {
    name: "judge",
    type: "llm_judge",
    config: {},
  },
];

describe("CompareExperimentsActionsPanel export helpers", () => {
  describe("resolvePassedStatus", () => {
    it("should fall back to the first item status when summaries are missing", () => {
      // Arrange
      const items = [createExperimentItem({ status: RunStatus.PASSED })];
      const row = createCompareRow({ experiment_items: items });

      // Act
      const status = resolvePassedStatus(row, items);

      // Assert
      expect(status).toBe(RunStatus.PASSED);
    });

    it("should resolve failed when summaries are mixed", () => {
      // Arrange
      const row = createCompareRow({
        run_summaries_by_experiment: {
          "experiment-1": {
            passed_runs: 1,
            total_runs: 1,
            status: RunStatus.PASSED,
          },
          "experiment-2": {
            passed_runs: 0,
            total_runs: 1,
            status: RunStatus.FAILED,
          },
        },
      });

      // Act
      const status = resolvePassedStatus(row, []);

      // Assert
      expect(status).toBe(RunStatus.FAILED);
    });

    it("should resolve passed when all summaries are passed", () => {
      // Arrange
      const row = createCompareRow({
        run_summaries_by_experiment: {
          "experiment-1": {
            passed_runs: 1,
            total_runs: 1,
            status: RunStatus.PASSED,
          },
          "experiment-2": {
            passed_runs: 1,
            total_runs: 1,
            status: RunStatus.PASSED,
          },
        },
      });

      // Act
      const status = resolvePassedStatus(row, []);

      // Assert
      expect(status).toBe(RunStatus.PASSED);
    });

    it("should resolve skipped when all summaries are skipped without evaluators", () => {
      // Arrange
      const row = createCompareRow({
        run_summaries_by_experiment: {
          "experiment-1": {
            passed_runs: 0,
            total_runs: 0,
            status: RunStatus.SKIPPED,
          },
          "experiment-2": {
            passed_runs: 0,
            total_runs: 0,
            status: RunStatus.SKIPPED,
          },
        },
      });

      // Act
      const status = resolvePassedStatus(row, []);

      // Assert
      expect(status).toBe(RunStatus.SKIPPED);
    });

    it("should use an experiment summary before falling back to item status", () => {
      // Arrange
      const items = [createExperimentItem({ status: RunStatus.FAILED })];
      const row = createCompareRow({
        experiment_items: items,
        run_summaries_by_experiment: {
          "experiment-1": {
            passed_runs: 1,
            total_runs: 1,
            status: RunStatus.PASSED,
          },
        },
      });

      // Act
      const status = resolvePassedStatus(row, items, "experiment-1");

      // Assert
      expect(status).toBe(RunStatus.PASSED);
    });

    it("should suppress skipped summaries when the row has evaluators", () => {
      // Arrange
      const row = createCompareRow({
        evaluators,
        run_summaries_by_experiment: {
          "experiment-1": {
            passed_runs: 0,
            total_runs: 0,
            status: RunStatus.SKIPPED,
          },
        },
      });

      // Act
      const status = resolvePassedStatus(row, [], "experiment-1");

      // Assert
      expect(status).toBeUndefined();
    });
  });

  describe("processPassedExportColumn", () => {
    it("should write status without assertion columns when assertion_results is empty", () => {
      // Arrange
      const items = [
        createExperimentItem({
          status: RunStatus.PASSED,
          assertion_results: [],
        }),
      ];
      const row = createCompareRow({ experiment_items: items });
      const accumulator: Record<string, unknown> = {};

      // Act
      processPassedExportColumn(row, items, accumulator);

      // Assert
      expect(accumulator).toEqual({
        status: RunStatus.PASSED,
      });
    });

    it("should write prefixed status and assertion result fields", () => {
      // Arrange
      const items = [
        createExperimentItem({
          status: RunStatus.FAILED,
          assertion_results: [
            {
              value: "is_correct",
              passed: false,
              reason: "Expected a different answer",
            },
          ],
        }),
      ];
      const row = createCompareRow({ experiment_items: items });
      const accumulator: Record<string, unknown> = {};

      // Act
      processPassedExportColumn(row, items, accumulator, "Experiment A.");

      // Assert
      expect(accumulator).toEqual({
        "Experiment A.status": RunStatus.FAILED,
        "Experiment A.assertion_1.name": "is_correct",
        "Experiment A.assertion_1.result": "failed",
        "Experiment A.assertion_1.reason": "Expected a different answer",
      });
    });

    it("should write dash when skipped status is suppressed", () => {
      // Arrange
      const items = [
        createExperimentItem({
          status: RunStatus.SKIPPED,
        }),
      ];
      const row = createCompareRow({
        evaluators,
        experiment_items: items,
      });
      const accumulator: Record<string, unknown> = {};

      // Act
      processPassedExportColumn(row, items, accumulator);

      // Assert
      expect(accumulator).toEqual({
        status: "-",
      });
    });
  });
});
