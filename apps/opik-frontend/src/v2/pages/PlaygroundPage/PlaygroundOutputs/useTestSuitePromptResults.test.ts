import { describe, it, expect } from "vitest";
import { areAllRowItemsScored } from "./useTestSuitePromptResults";
import { ExperimentsCompare } from "@/types/datasets";
import { MetricType } from "@/types/test-suites";
import { DATASET_ITEM_SOURCE } from "@/types/datasets";

const makeLLMJudgeEvaluator = (assertions: string[]) => ({
  name: "llm_judge",
  type: MetricType.LLM_AS_JUDGE,
  config: {
    schema: assertions.map((name) => ({
      name,
      type: "BOOLEAN",
      description: "",
    })),
  },
});

const makeRow = (
  overrides: Partial<ExperimentsCompare> = {},
): ExperimentsCompare => ({
  id: "row-1",
  data: {},
  source: DATASET_ITEM_SOURCE.manual,
  created_at: "",
  last_updated_at: "",
  experiment_items: [],
  ...overrides,
});

const makeItem = (
  overrides: Partial<ExperimentsCompare["experiment_items"][0]> = {},
) => ({
  id: "ei-1",
  experiment_id: "exp-1",
  dataset_item_id: "ds-1",
  input: {},
  output: {},
  created_at: "",
  last_updated_at: "",
  ...overrides,
});

describe("areAllRowItemsScored", () => {
  describe("no experiment items", () => {
    it("should return false when items list is empty", () => {
      const row = makeRow({ experiment_items: [] });
      expect(areAllRowItemsScored(row)).toBe(false);
    });
  });

  describe("no evaluators defined", () => {
    it("should return true when evaluators is an empty array", () => {
      const row = makeRow({
        evaluators: [],
        experiment_items: [makeItem()],
      });
      expect(areAllRowItemsScored(row)).toBe(true);
    });

    it("should fall back to status check when evaluators is undefined", () => {
      const row = makeRow({
        evaluators: undefined,
        experiment_items: [makeItem({ status: "completed" as never })],
      });
      expect(areAllRowItemsScored(row)).toBe(true);
    });

    it("should return false when evaluators is undefined and status is null", () => {
      const row = makeRow({
        evaluators: undefined,
        experiment_items: [makeItem({ status: undefined })],
      });
      expect(areAllRowItemsScored(row)).toBe(false);
    });
  });

  describe("with assertions", () => {
    const evaluators = [makeLLMJudgeEvaluator(["is_correct", "is_helpful"])];

    it("should return true when all items have enough assertion results", () => {
      const row = makeRow({
        evaluators,
        experiment_items: [
          makeItem({
            assertion_results: [
              { value: "is_correct", passed: true },
              { value: "is_helpful", passed: false },
            ],
          }),
        ],
      });
      expect(areAllRowItemsScored(row)).toBe(true);
    });

    it("should return false when items have fewer assertion results than expected", () => {
      const row = makeRow({
        evaluators,
        experiment_items: [
          makeItem({
            assertion_results: [{ value: "is_correct", passed: true }],
          }),
        ],
      });
      expect(areAllRowItemsScored(row)).toBe(false);
    });

    it("should return false when assertion_results is undefined", () => {
      const row = makeRow({
        evaluators,
        experiment_items: [makeItem({ assertion_results: undefined })],
      });
      expect(areAllRowItemsScored(row)).toBe(false);
    });

    it("should require all items to be scored", () => {
      const row = makeRow({
        evaluators,
        experiment_items: [
          makeItem({
            id: "ei-1",
            assertion_results: [
              { value: "is_correct", passed: true },
              { value: "is_helpful", passed: true },
            ],
          }),
          makeItem({
            id: "ei-2",
            assertion_results: [],
          }),
        ],
      });
      expect(areAllRowItemsScored(row)).toBe(false);
    });
  });

  describe("non-llm-judge evaluator", () => {
    it("should treat non-llm-judge evaluator as zero assertions and check status", () => {
      const row = makeRow({
        evaluators: [{ name: "custom", type: "custom", config: {} }],
        experiment_items: [makeItem({ status: "completed" as never })],
      });
      expect(areAllRowItemsScored(row)).toBe(true);
    });
  });
});
