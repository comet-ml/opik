import { describe, expect, it } from "vitest";
import {
  isAggregatedScore,
  isAggregatedItem,
  aggregateTrialItems,
  AggregatedExperimentItem,
  AggregatedFeedbackScore,
} from "./trials";
import { ExperimentItem } from "@/types/datasets";
import { TraceFeedbackScore } from "@/types/traces";
import { FEEDBACK_SCORE_TYPE } from "@/types/traces";

const makeItem = (overrides: Partial<ExperimentItem> = {}): ExperimentItem => ({
  id: "item-1",
  experiment_id: "exp-1",
  dataset_item_id: "ds-item-1",
  input: {},
  output: {},
  created_at: "2024-01-01T00:00:00Z",
  last_updated_at: "2024-01-01T00:00:00Z",
  ...overrides,
});

const makeScore = (name: string, value: number): TraceFeedbackScore => ({
  name,
  value,
  source: FEEDBACK_SCORE_TYPE.sdk,
});

describe("isAggregatedScore", () => {
  it("should return false for undefined", () => {
    expect(isAggregatedScore(undefined)).toBe(false);
  });

  it("should return false for a regular feedback score", () => {
    expect(isAggregatedScore(makeScore("test", 0.5))).toBe(false);
  });

  it("should return true for an aggregated feedback score", () => {
    const score: AggregatedFeedbackScore = {
      ...makeScore("test", 0.5),
      stdDev: 0.1,
      trialValues: [0.4, 0.6],
    };
    expect(isAggregatedScore(score)).toBe(true);
  });
});

describe("isAggregatedItem", () => {
  it("should return false for undefined", () => {
    expect(isAggregatedItem(undefined)).toBe(false);
  });

  it("should return false for a regular experiment item", () => {
    expect(isAggregatedItem(makeItem())).toBe(false);
  });

  it("should return false for trialCount of 1", () => {
    const item = {
      ...makeItem(),
      trialCount: 1,
      trialItems: [makeItem()],
    } as AggregatedExperimentItem;
    expect(isAggregatedItem(item)).toBe(false);
  });

  it("should return true for trialCount > 1", () => {
    const item = {
      ...makeItem(),
      trialCount: 3,
      trialItems: [makeItem(), makeItem(), makeItem()],
    } as AggregatedExperimentItem;
    expect(isAggregatedItem(item)).toBe(true);
  });
});

describe("aggregateTrialItems", () => {
  it("should average durations", () => {
    const items = [
      makeItem({ duration: 100 }),
      makeItem({ duration: 200 }),
      makeItem({ duration: 300 }),
    ];
    const result = aggregateTrialItems(items);
    expect(result.duration).toBe(200);
  });

  it("should average total_estimated_cost", () => {
    const items = [
      makeItem({ total_estimated_cost: 0.01 }),
      makeItem({ total_estimated_cost: 0.03 }),
    ];
    const result = aggregateTrialItems(items);
    expect(result.total_estimated_cost).toBe(0.02);
  });

  it("should average usage tokens with rounding", () => {
    const items = [
      makeItem({
        usage: { prompt_tokens: 100, completion_tokens: 50, total_tokens: 150 },
      }),
      makeItem({
        usage: {
          prompt_tokens: 200,
          completion_tokens: 100,
          total_tokens: 300,
        },
      }),
      makeItem({
        usage: {
          prompt_tokens: 300,
          completion_tokens: 150,
          total_tokens: 450,
        },
      }),
    ];
    const result = aggregateTrialItems(items);
    expect(result.usage).toEqual({
      prompt_tokens: 200,
      completion_tokens: 100,
      total_tokens: 300,
    });
  });

  it("should return undefined usage when no items have usage", () => {
    const items = [makeItem(), makeItem()];
    const result = aggregateTrialItems(items);
    expect(result.usage).toBeUndefined();
  });

  it("should skip items without usage in average", () => {
    const items = [
      makeItem({
        usage: { prompt_tokens: 100, completion_tokens: 50, total_tokens: 150 },
      }),
      makeItem(),
    ];
    const result = aggregateTrialItems(items);
    expect(result.usage).toEqual({
      prompt_tokens: 100,
      completion_tokens: 50,
      total_tokens: 150,
    });
  });

  it("should return undefined duration when no items have duration", () => {
    const items = [makeItem(), makeItem()];
    const result = aggregateTrialItems(items);
    expect(result.duration).toBeUndefined();
  });

  it("should set trialCount and trialItems", () => {
    const items = [makeItem({ id: "a" }), makeItem({ id: "b" })];
    const result = aggregateTrialItems(items);
    expect(result.trialCount).toBe(2);
    expect(result.trialItems).toHaveLength(2);
    expect(result.trialItems[0].id).toBe("a");
    expect(result.trialItems[1].id).toBe("b");
  });

  it("should use first item as base for non-aggregated fields", () => {
    const items = [
      makeItem({ id: "first", trace_id: "trace-1", input: { text: "hello" } }),
      makeItem({ id: "second", trace_id: "trace-2", input: { text: "world" } }),
    ];
    const result = aggregateTrialItems(items);
    expect(result.id).toBe("first");
    expect(result.trace_id).toBe("trace-1");
    expect(result.input).toEqual({ text: "hello" });
  });

  describe("feedback scores aggregation", () => {
    it("should average feedback scores by name", () => {
      const items = [
        makeItem({
          feedback_scores: [makeScore("accuracy", 0.8)],
        }),
        makeItem({
          feedback_scores: [makeScore("accuracy", 0.6)],
        }),
      ];
      const result = aggregateTrialItems(items);
      const score = result.feedback_scores?.find(
        (s) => s.name === "accuracy",
      ) as AggregatedFeedbackScore;
      expect(score.value).toBe(0.7);
      expect(score.trialValues).toEqual([0.8, 0.6]);
    });

    it("should compute stdDev for feedback scores", () => {
      const items = [
        makeItem({ feedback_scores: [makeScore("accuracy", 0.8)] }),
        makeItem({ feedback_scores: [makeScore("accuracy", 0.6)] }),
      ];
      const result = aggregateTrialItems(items);
      const score = result.feedback_scores?.find(
        (s) => s.name === "accuracy",
      ) as AggregatedFeedbackScore;
      expect(score.stdDev).toBeCloseTo(0.1, 5);
    });

    it("should handle multiple score names independently", () => {
      const items = [
        makeItem({
          feedback_scores: [
            makeScore("accuracy", 0.8),
            makeScore("relevance", 0.5),
          ],
        }),
        makeItem({
          feedback_scores: [
            makeScore("accuracy", 0.6),
            makeScore("relevance", 0.9),
          ],
        }),
      ];
      const result = aggregateTrialItems(items);
      const accuracy = result.feedback_scores?.find(
        (s) => s.name === "accuracy",
      ) as AggregatedFeedbackScore;
      const relevance = result.feedback_scores?.find(
        (s) => s.name === "relevance",
      ) as AggregatedFeedbackScore;
      expect(accuracy.value).toBe(0.7);
      expect(relevance.value).toBe(0.7);
    });

    it("should return undefined when no items have feedback scores", () => {
      const items = [makeItem(), makeItem()];
      const result = aggregateTrialItems(items);
      expect(result.feedback_scores).toBeUndefined();
    });

    it("should only average trials that have the score", () => {
      const items = [
        makeItem({ feedback_scores: [makeScore("accuracy", 0.8)] }),
        makeItem({ feedback_scores: [] }),
      ];
      const result = aggregateTrialItems(items);
      const score = result.feedback_scores?.find(
        (s) => s.name === "accuracy",
      ) as AggregatedFeedbackScore;
      expect(score.value).toBe(0.8);
      expect(score.trialValues).toEqual([0.8]);
    });

    it("should compute zero stdDev for identical scores", () => {
      const items = [
        makeItem({ feedback_scores: [makeScore("accuracy", 0.5)] }),
        makeItem({ feedback_scores: [makeScore("accuracy", 0.5)] }),
        makeItem({ feedback_scores: [makeScore("accuracy", 0.5)] }),
      ];
      const result = aggregateTrialItems(items);
      const score = result.feedback_scores?.find(
        (s) => s.name === "accuracy",
      ) as AggregatedFeedbackScore;
      expect(score.stdDev).toBe(0);
    });
  });
});
