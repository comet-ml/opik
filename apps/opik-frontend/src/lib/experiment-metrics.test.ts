import { describe, it, expect } from "vitest";
import { aggregateExperimentMetrics } from "./experiment-metrics";
import { Experiment, EXPERIMENT_TYPE } from "@/types/datasets";

const makeExperiment = (overrides: Partial<Experiment> = {}): Experiment => ({
  id: "exp-1",
  dataset_id: "ds-1",
  dataset_name: "test",
  type: EXPERIMENT_TYPE.TRIAL,
  status: "completed",
  name: "test-exp",
  trace_count: 10,
  created_at: "2025-01-01T00:00:00Z",
  last_updated_at: "2025-01-01T00:00:00Z",
  ...overrides,
});

describe("aggregateExperimentMetrics", () => {
  describe("empty input", () => {
    it("should return undefined metrics for empty experiments", () => {
      const result = aggregateExperimentMetrics([], "objective");
      expect(result).toEqual({
        score: undefined,
        cost: undefined,
        totalCost: undefined,
        latency: undefined,
        totalTraceCount: 0,
        totalDatasetItemCount: 0,
      });
    });
  });

  describe("score weighting by dataset_item_count", () => {
    it("should weight scores by dataset_item_count when available", () => {
      const experiments = [
        makeExperiment({
          id: "e1",
          trace_count: 20,
          dataset_item_count: 10,
          feedback_scores: [{ name: "accuracy", value: 0.8 }],
        }),
        makeExperiment({
          id: "e2",
          trace_count: 10,
          dataset_item_count: 5,
          feedback_scores: [{ name: "accuracy", value: 0.6 }],
        }),
      ];

      const result = aggregateExperimentMetrics(experiments, "accuracy");

      // Weighted average: (0.8 * 10 + 0.6 * 5) / (10 + 5) = 11 / 15
      expect(result.score).toBeCloseTo(11 / 15);
      expect(result.totalTraceCount).toBe(30);
      expect(result.totalDatasetItemCount).toBe(15);
    });

    it("should fall back to trace_count when dataset_item_count is undefined", () => {
      const experiments = [
        makeExperiment({
          id: "e1",
          trace_count: 10,
          dataset_item_count: undefined,
          feedback_scores: [{ name: "accuracy", value: 0.8 }],
        }),
        makeExperiment({
          id: "e2",
          trace_count: 5,
          dataset_item_count: undefined,
          feedback_scores: [{ name: "accuracy", value: 0.6 }],
        }),
      ];

      const result = aggregateExperimentMetrics(experiments, "accuracy");

      // Falls back to trace_count: (0.8 * 10 + 0.6 * 5) / (10 + 5)
      expect(result.score).toBeCloseTo(11 / 15);
    });

    it("should not weight by trace_count when runs_per_item > 1", () => {
      // runs_per_item = 2: 10 dataset items, 20 traces
      const experiments = [
        makeExperiment({
          id: "e1",
          trace_count: 20,
          dataset_item_count: 10,
          feedback_scores: [{ name: "accuracy", value: 1.0 }],
        }),
        // runs_per_item = 1: 10 dataset items, 10 traces
        makeExperiment({
          id: "e2",
          trace_count: 10,
          dataset_item_count: 10,
          feedback_scores: [{ name: "accuracy", value: 0.0 }],
        }),
      ];

      const result = aggregateExperimentMetrics(experiments, "accuracy");

      // Equal dataset items → equal weight: (1.0 * 10 + 0.0 * 10) / 20 = 0.5
      expect(result.score).toBeCloseTo(0.5);
    });
  });

  describe("cost aggregation", () => {
    it("should sum total cost and compute per-trace average", () => {
      const experiments = [
        makeExperiment({
          id: "e1",
          trace_count: 10,
          total_estimated_cost: 5.0,
        }),
        makeExperiment({
          id: "e2",
          trace_count: 10,
          total_estimated_cost: 3.0,
        }),
      ];

      const result = aggregateExperimentMetrics(experiments);

      expect(result.totalCost).toBe(8.0);
      expect(result.cost).toBeCloseTo(0.4); // 8 / 20
    });
  });

  describe("latency aggregation", () => {
    it("should weight latency by trace_count", () => {
      const experiments = [
        makeExperiment({
          id: "e1",
          trace_count: 10,
          duration: { p50: 2000, p90: 3000, p99: 4000 },
        }),
        makeExperiment({
          id: "e2",
          trace_count: 10,
          duration: { p50: 4000, p90: 5000, p99: 6000 },
        }),
      ];

      const result = aggregateExperimentMetrics(experiments);

      // (2000/1000 * 10 + 4000/1000 * 10) / 20 = (20 + 40) / 20 = 3.0
      expect(result.latency).toBeCloseTo(3.0);
    });
  });

  describe("experiment_scores fallback", () => {
    it("should use experiment_scores when feedback_scores lacks the objective", () => {
      const experiments = [
        makeExperiment({
          id: "e1",
          trace_count: 5,
          dataset_item_count: 5,
          feedback_scores: [],
          experiment_scores: [{ name: "accuracy", value: 0.9 }],
        }),
      ];

      const result = aggregateExperimentMetrics(experiments, "accuracy");

      expect(result.score).toBeCloseTo(0.9);
    });
  });

  describe("missing data handling", () => {
    it("should return undefined score when no objective name provided", () => {
      const experiments = [
        makeExperiment({
          feedback_scores: [{ name: "accuracy", value: 0.8 }],
        }),
      ];

      const result = aggregateExperimentMetrics(experiments);

      expect(result.score).toBeUndefined();
    });

    it("should return undefined score when no experiments have the objective", () => {
      const experiments = [
        makeExperiment({
          feedback_scores: [{ name: "other", value: 0.8 }],
        }),
      ];

      const result = aggregateExperimentMetrics(experiments, "accuracy");

      expect(result.score).toBeUndefined();
    });

    it("should skip experiments with zero trace_count for latency", () => {
      const experiments = [
        makeExperiment({
          id: "e1",
          trace_count: 0,
          dataset_item_count: 0,
          duration: { p50: 1000, p90: 2000, p99: 3000 },
        }),
      ];

      const result = aggregateExperimentMetrics(experiments);

      expect(result.latency).toBeUndefined();
    });
  });
});
